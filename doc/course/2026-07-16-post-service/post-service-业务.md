# post-service

> 本文描述 2026-07-29 优化后的实际实现。事实来源为 `dating-server/post-service`、`proto/post/post.proto` 和 Flyway migration。

## 功能清单

| 编号 | 功能 | 入口/触发器 | 核心实现 |
| --- | --- | --- | --- |
| F001 | CreatePost | `PostGrpcService.createPost` | 四表本地事务 + 提交后缓存/冷启动池 |
| F002 | GetPostDetail | `PostGrpcService.getPostDetail` | Caffeine → Redis → DB，动态字段单独组装 |
| F003 | ListUserPosts | `PostGrpcService.listUserPosts` | `post_id` 游标 + 图片/计数/点赞批量查询 |
| F004 | DeletePost | `PostGrpcService.deletePost` | 作者校验 + 逻辑删帖 + 提交后缓存失效 |
| F005 | ActionLike | `PostGrpcService.actionLike` | PostgreSQL UPSERT 幂等 + Redis 点赞写合并 |
| F006 | CreateComment | `PostGrpcService.createComment` | 评论与评论数同一 DB 事务 |
| F007 | ListComments | `PostGrpcService.listComments` | 一级评论 ZSet 窗口 + DB 回源 |
| F008 | DeleteComment | `PostGrpcService.deleteComment` | 按业务 `comment_id` 删除 + 评论数同事务减少 |
| F009 | GetRecommendFeed | `PostGrpcService.getRecommendFeed` | 8:1:1 三路混排 + Bloom + 批量详情 |
| F010 | Fanout Outbox | `PostFanoutOutboxJob` | 事务内 Outbox + RocketMQ 最终投递 |
| F011 | LikeFlush | `LikeFlushJob` | 每分钟把 Redis 点赞增量写回 DB |
| F012 | FeedScore | `FeedScoreJob` | 每 5 分钟重建近 3 天热门池 |
| F013 | Outbox Cleanup | `PostFanoutOutboxCleanupJob` | 每小时清理 7 天前已投递事件 |

## 一、公开接口与统一边界

`proto/post/post.proto` 定义 9 个 RPC：

```text
CreatePost / GetPostDetail / ListUserPosts / DeletePost
ActionLike
CreateComment / ListComments / DeleteComment
GetRecommendFeed
```

调用分层：

```text
mobile-gateway
  → UserIdInterceptor
  → PostGrpcService
  → Service
  → Manager
  → Mapper / Redis / RocketMQ / user-service
```

点赞枚举的真实值：

```proto
LIKE = 0;
UNLIKE = 1;
```

---

## 二、帖子生命周期

### F001：CreatePost

#### 入口校验

```text
content 不能为空
content ≤ 1024 字符
imageKeys ≤ 9 个
```

post-service 只保存图片 key，不负责预签名或对象上传。

#### 本地事务

`PostWriteService.createPost()` 直接标注：

```java
@Transactional(rollbackFor = Exception.class)
public long createPost(...)
```

事务内按顺序执行：

```text
Snowflake 生成 postId
  → INSERT posts
  → INSERT post_images（逐条）
  → INSERT post_stats(like_count=0, comment_count=0)
  → INSERT post_fanout_outbox(status=PENDING)
```

原来的事务注解放在同类内部方法上，存在 Spring 自调用失效问题；现在事务注解位于 gRPC 实际调用的 public 方法，四类 DB 记录原子提交。

#### 事务提交后

通过 `TransactionSynchronization.afterCommit()` 执行：

```text
写帖子公共详情 L1/L2 缓存
  → 按作者性别加入冷启动池
```

只有数据库事务成功才执行 Redis 操作。

冷启动池：

```text
putao:feed:cold_start:pool:male
putao:feed:cold_start:pool:female
```

- score：当前 epoch second
- 最大 10000 条
- TTL：7 天

写扩散不在提交后直接发 MQ，而是由事务内 Outbox 保证后续投递。

---

### F002：GetPostDetail

帖子详情拆成两部分：

| 部分 | 字段 | 读取方式 |
| --- | --- | --- |
| 公共静态字段 | postId、作者、正文、状态、图片、创建时间 | L1 Caffeine + L2 Redis Cache-Aside |
| 动态/个性化字段 | likeCount、commentCount、isLiked | DB 基准 + Redis 点赞增量 + 点赞关系 |

#### 第一层：Caffeine

```text
容量：50000
TTL：15 秒
```

只用于吸收单实例内热点帖子对 Redis 的压力。

#### 第二层：Redis

```text
Key: putao:post:detail:v1:<postId>
Value: JSON CacheValue
TTL: 7 天 + 0～20 分钟随机抖动
```

随机 TTL 避免大量详情 key 同时过期。

#### 缓存穿透

数据库不存在时写负缓存：

```text
found=false
TTL=30 秒
```

恶意或重复请求不存在的 postId 不会每次进入 PostgreSQL。

#### 缓存击穿

L1、L2 都 miss 时抢分布式锁：

```text
putao:lock:post:detail:<postId>
等待最多 200ms
lease 5s
```

等待后再次检查缓存，再决定是否回源 DB。Redis/Redisson 故障时降级到 DB，不让缓存故障直接变成详情不可用。

#### 动态计数

```text
likeCount =
  post_stats.like_count
  + GET putao:post:stat:incr:<postId>:likes

commentCount = post_stats.comment_count
```

评论数已改为评论事务内直接更新 DB；只有点赞采用 Redis 写合并。

#### `isLiked`

单独查询：

```sql
post_likes
WHERE user_id = ? AND post_id = ? AND status = 1
```

不能放进公共详情缓存，否则不同用户会拿到相同的个性化结果。

#### 可见性

- `status=NORMAL`：所有调用者可见。
- 非 NORMAL：仅作者本人可见。
- `deleted=1`：不存在。

---

### 两级缓存写入与失效

#### 创建帖子

事务提交后直接预热 L1 和 L2。

#### 删除帖子

数据库事务提交后：

```text
删除当前实例 Caffeine
DEL Redis detail key
PUBLISH putao:post:detail:evict <postId>
其他实例收到消息后删除本地 Caffeine
```

Redis Pub/Sub 不是持久消息；即使个别实例漏收，本地缓存也会在 15 秒内自然过期。

当前没有帖子编辑接口，所以无需处理正文更新。未来增加编辑/审核时，必须复用相同的“DB 提交后失效”逻辑。

---

### F003：ListUserPosts

分页参数在 Service 内限制为 1～50。

查询使用：

```sql
WHERE user_id = ?
  AND deleted = 0
  AND status = 1
  AND post_id < :cursor
ORDER BY post_id DESC
LIMIT pageSize + 1
```

多取一条判断 `hasMore`，返回页最后一条作为 `nextCursor`，不会跳过记录。

优化后的批量组装：

```text
1 次 posts 列表
1 次 post_images IN 查询
1 次 post_stats 批量查询
1 次 Redis MGET 点赞增量
1 次 post_likes IN 查询
```

不再逐帖执行图片和计数查询。

---

### F004：DeletePost

```text
查帖子
  → 校验当前用户是作者
  → posts.deleted=1
  → 物理删除 post_images
  → DB COMMIT
  → 删除两级详情缓存
  → 从两个冷启动池 ZREM
```

点赞、评论、统计和 timeline 引用保留；后续批量详情查询会过滤已删除帖子。

---

## 三、点赞与计数写合并

### F005：ActionLike

#### 帖子校验

使用 `PostManager.getByPostId()`，帖子不存在立即抛错，不再允许产生孤儿点赞关系。

#### 数据库幂等

```sql
INSERT INTO post_likes(...)
VALUES(...)
ON CONFLICT(user_id, post_id)
DO UPDATE SET status = EXCLUDED.status
WHERE post_likes.status <> EXCLUDED.status;
```

代码直接使用 Mapper 影响行数：

```text
affected=0 → 已是目标状态，不更新计数
affected=1 → 状态真实变化，更新 Redis delta
```

原来的“先 SELECT 再 UPSERT”会在并发下重复加计数，现已移除。

#### Redis 原子写入

一次 Lua 完成：

```text
INCRBY like-delta ±1
EXPIRE like-delta 7天
SADD likeUpdatedSet postId
```

Key：

```text
putao:post:stat:incr:<postId>:likes
putao:post:like:updated_set
```

三个操作不会出现“delta 已写但 dirty 标记没写”的中间状态。

---

### F011：LikeFlush

每 60 秒执行，ShedLock 锁名：

```text
post.likeFlush
```

流程：

```text
SRANDMEMBER likeUpdatedSet 100
  → Lua GET delta + SET 0
  → UPDATE post_stats
       SET like_count=GREATEST(0, like_count+delta)
  → Lua 检查是否产生新 delta
       无新 delta → SREM dirty
       有新 delta → 保留 dirty
```

DB 更新失败时，当前进程把 delta 加回 Redis 并保留 dirty 标记。

剩余边界：Redis 清零之后、DB 更新之前若进程被强杀，仍存在小的计数丢失窗口。点赞关系表是事实来源，因此生产环境仍应配置周期性对账任务或离线重算；展示计数不是扣费/库存，不为它额外引入 MQ。

---

## 四、评论

### F006：CreateComment

入口：

```text
content 非空
content ≤ 512
```

事务内：

```text
校验帖子存在
  → 校验 root/parent 属于同一帖子和评论线程
  → 计算 replyToUserId
  → INSERT post_comments
  → UPDATE post_stats SET comment_count=comment_count+1
```

评论和评论数同一本地事务，不再走 Redis 评论 delta，也不再需要 `CommentFlushJob`。

提交后只把一级评论写入 ZSet：

```text
putao:post:comments:<postId>
最多 200 条
TTL 7 天
```

回复不进入一级评论窗口，Redis 热路和 DB 冷路口径保持一致。

### F007：ListComments

- `pageSize` 限制 1～50。
- Redis 请求 `pageSize+1` 个一级 commentId。
- Redis 不足时回源 DB。
- `hasMore = queried.size > pageSize`。
- `nextCursor` 使用实际返回页最后一条，不跳过额外记录。

### F008：DeleteComment

按业务字段查询：

```sql
WHERE comment_id = ? AND deleted = 0
```

不再错误使用 `selectById(commentId)` 查询内部自增 `id`。

事务内：

```text
作者校验
  → deleted=1
  → post_stats.comment_count-1
```

提交后从评论 ZSet 移除。

---

## 五、推荐 Feed

### F009：GetRecommendFeed

#### 三路来源

| 来源 | Redis Key | 目标 |
| --- | --- | --- |
| 热门 | `putao:feed:pool:recommend:<gender>` | 主力内容 |
| 好友 | `putao:user:timeline:<userId>` | 社交关系内容 |
| 冷启动 | `putao:feed:cold_start:pool:<gender>` | 新帖扶持 |

每 10 个位置：

```text
1,2,4,5,7,8,9,10 → recommend
3 → friend
6 → cold_start
```

指定来源为空时：

```text
优先 recommend
  → cold_start
  → friend
```

旧实现先连续 append 热门内容，导致第 3、第 6 位已被占用；新实现先在 Candidate 层按 slot 选择，不再出现强插失效。

#### Bloom 去重

```text
Key: putao:user:read:bloom:<userId>
capacity=5000
false positive=1%
TTL=7天
```

#### 批量组装

候选 ID 选完后统一：

```text
批量 posts
批量 post_images
批量 post_stats
Redis MGET like delta
批量 post_likes
```

不再为每个候选执行四次独立查询。

#### 游标

```text
<recommendOffset>:<coldStartOffset>
```

offset 按实际检查过的来源候选推进，Bloom 命中或帖子失效也会推进，避免下一页反复扫描相同脏数据。

---

### F012：FeedScore

每 5 分钟：

```text
查近 3 天正常帖子
  → 批量读取 DB 计数和 Redis 点赞 delta
  → 批量获取作者性别
  → 内存计算热度
  → 男女分桶，各取 Top 3000
  → 写 tmp ZSet
  → RENAME 正式池
```

热度公式：

```text
(10 + likes + 3 × comments) / (hours + 2)^1.5
```

某一分桶为空时直接删除对应正式池，不再对不存在的临时 key 执行 `RENAME`。

---

## 六、user-service 性别读取

```text
L1 Caffeine 30秒
  → L2 Redis 6小时
  → user-service GetProfile / BatchGetProfile
```

已移除 userId 奇偶取模桩和每次创建 `ManagedChannel` 的实现。Channel 由 Spring 单例管理并在服务关闭时执行 `shutdown`。

`user.proto` 当前仍没有好友列表 RPC，因此 `getFriendUserIds()` 继续返回空列表。这意味着好友 timeline 暂时没有真实数据，但不影响热门和冷启动 Feed。

---

## 七、为什么保留 MQ，不使用延时消息

### F010：Fanout Outbox

好友写扩散是：

```text
一条帖子 × N 个好友 timeline 写入
```

不适合阻塞发帖请求，所以保留 RocketMQ。

为了避免“帖子提交后进程崩溃，MQ 未发送”，发帖事务同时写：

```text
post_fanout_outbox(status=PENDING)
```

`PostFanoutOutboxJob` 每 5 秒扫描：

```text
PENDING + next_retry_at 到期
  → Producer 本地最多重试 3 次
  → 成功：DELIVERED
  → 失败：attempts+1，指数退避，最大 5 分钟
```

Producer 或进程在标记前崩溃可能造成重复消息；Consumer 用同一个 postId 对 timeline 执行 ZADD，天然幂等。

RocketMQ Consumer：

```text
查作者好友
  → 对每个 follower ZADD timeline
  → 只保留 100 条
  → TTL 7 天
```

消费异常由 RocketMQ 最多重试 16 次。

`PostFanoutOutboxCleanupJob` 每小时删除 7 天前、状态为 DELIVERED 的记录，每批最多 10000 条。

### 不使用延时消息

当前没有“指定未来时刻发布”“定时提醒”或“等待审核后自动曝光”等业务，因此不需要 RocketMQ 延时消息。

Outbox 的失败退避由数据库 `next_retry_at` + 定时扫描实现，不依赖延时消息，便于查询、补单和运维。

---

## 八、存储清单

### 数据表

| 表 | 用途 |
| --- | --- |
| `posts` | 帖子主记录 |
| `post_images` | 图片 key |
| `post_stats` | 点赞/评论基准 |
| `post_likes` | 点赞事实与幂等 |
| `post_comments` | 评论及回复关系 |
| `post_fanout_outbox` | fanout 可靠投递 |
| `shedlock` | 定时任务互斥 |

### 关键 Redis

| Key | 类型 | TTL/容量 |
| --- | --- | --- |
| `putao:post:detail:v1:<postId>` | String JSON | 7d + jitter |
| Caffeine post detail | 本地缓存 | 15s / 50000 |
| `putao:post:stat:incr:<postId>:likes` | String | 7d |
| `putao:post:like:updated_set` | Set | dirty posts |
| `putao:post:comments:<postId>` | ZSet | 7d / 200 |
| `putao:user:timeline:<userId>` | ZSet | 7d / 100 |
| `putao:feed:cold_start:pool:<gender>` | ZSet | 7d / 10000 |
| `putao:feed:pool:recommend:<gender>` | ZSet | 7d / 3000 |
| `putao:user:read:bloom:<userId>` | Bloom | 7d / 5000 |
| `putao:post:user:gender:<userId>` | String | 6h |

旧 Nacos 配置可能提供 `putao:post` 前缀，`RedisKey.setPrefix()` 会规范化为根前缀 `putao`，避免产生 `putao:post:post:*`。

---

## 九、仍需关注的生产边界

| 项目 | 当前状态 | 后续建议 |
| --- | --- | --- |
| 好友列表 | user.proto 无 RPC，fanout Consumer 暂时无好友 | 增加正式好友/关注接口 |
| 点赞刷盘极端崩溃窗口 | DB 与 Redis 无法形成原子事务 | 周期性从 `post_likes` 对账热门帖子 |
| Cache Pub/Sub | 非持久，漏收后靠 15s TTL | 删除/封禁要求零陈旧时引入可靠失效事件 |
| Outbox 积压 | 已有重试和清理 | 增加 PENDING 数量、最老事件年龄告警 |
| 集成测试 | 已有单元测试，未启动真实 PG/Redis/MQ | 增加 Testcontainers 端到端测试 |

## 十、代码索引

| 主题 | 文件 |
| --- | --- |
| 两级详情缓存 | `PostDetailCacheManager`、`PostCacheInvalidationConfig` |
| 帖子读写 | `PostReadService`、`PostWriteService` |
| 批量详情 | `PostBatchReadManager` |
| 点赞 | `PostLikeManager`、`LikeFlushJob` |
| 评论 | `CommentService`、`PostCommentManager` |
| Feed | `FeedService`、`FeedScoreJob` |
| Outbox | `PostFanoutOutboxManager`、`PostFanoutOutboxService`、两个 Outbox Job |
| MQ | `PostFanoutProducer`、`PostFanoutConsumer` |
| 用户依赖 | `UserClient`、`GrpcClientConfig` |
