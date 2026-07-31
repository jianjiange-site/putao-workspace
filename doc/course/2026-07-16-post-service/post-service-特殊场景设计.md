# Post Service 特殊场景设计

> 本文只讲 Post Service 中值得单独学习的系统设计问题。用户功能清单见 `post-service-业务清单.md`，逐功能代码链路见 `post-service-业务流程详解.md`。

## 一、场景总览

| 场景 | 核心矛盾 | 当前方案 |
| --- | --- | --- |
| 高并发点赞计数 | 热点行强一致写与高吞吐冲突 | 点赞关系落库 + Redis 增量 + 定时合并 |
| 热门详情缓存 | 热点读取、缓存一致性与降级冲突 | Caffeine + Redis + 负缓存 + 单飞回源 |
| Feed 流 | 质量、社交、新内容曝光和分页稳定性冲突 | 三路位置混排 + Bloom + 批量组装 |
| 好友写扩散 | 发帖低延迟与 O(N) timeline 写入冲突 | Transactional Outbox + RocketMQ |
| 评论系统 | 楼中楼结构、列表性能与计数一致性冲突 | root/parent 建模 + 一级评论窗口 + DB 事务计数 |
| 游标与批量读取 | 动态数据翻页稳定性与 N+1 查询冲突 | 业务 ID 游标 + `pageSize+1` + 批量查询 |

## 二、高并发点赞计数

### 2.1 业务特征

点赞具有“高频、热点集中、单次价值低、允许展示计数短暂偏差”的特点。若每次点赞都执行：

```sql
UPDATE post_stats
SET like_count = like_count + 1
WHERE post_id = ?;
```

同一热门帖的所有请求会竞争同一行锁，同时制造大量 UPDATE 和 WAL。关系状态和展示计数因此被拆开：

- `post_likes`：谁点赞了什么，是事实来源；
- `post_stats.like_count`：已经合并的展示基准；
- Redis delta：尚未合并的实时变化。

### 2.2 幂等关系写入

`PostLikeManager` 使用 PostgreSQL UPSERT：

```sql
INSERT INTO post_likes(user_id, post_id, status, ...)
VALUES(...)
ON CONFLICT(user_id, post_id)
DO UPDATE SET status = EXCLUDED.status
WHERE post_likes.status <> EXCLUDED.status;
```

影响行数表达状态是否真的变化：

```text
affected = 0：已经是目标状态，不动计数
affected = 1：点赞状态变化，Redis delta ±1
```

相比“先 SELECT 再 UPDATE”，唯一约束和单条原子 SQL 消除了两个并发请求同时判断为未点赞、进而重复加计数的竞态。

### 2.3 Redis 原子写合并

状态真实变化后，一段 Lua 同时完成：

```text
INCRBY putao:post:stat:incr:<postId>:likes ±1
EXPIRE delta 7d
SADD putao:post:like:updated_set <postId>
```

原子化的目的不是让 PostgreSQL 和 Redis 形成分布式事务，而是避免 Redis 内出现“有增量却没有 dirty 标记”或“忘记设置 TTL”的中间状态。

### 2.4 读取与刷盘

```text
展示点赞数 = post_stats.like_count + Redis delta
```

`LikeFlushJob` 每 60 秒处理一批 dirty postId：

```text
SRANDMEMBER dirty set 100
  → Lua 读取并清零 delta
  → DB 执行 like_count = GREATEST(0, like_count + delta)
  → 没有新 delta 时移除 dirty
```

ShedLock 的 `post.likeFlush` 防止多个实例同时执行同一批刷盘任务。

### 2.5 失败窗口与边界

- DB 更新失败时，当前进程会把 delta 加回 Redis并保留 dirty。
- Redis 已清零、DB 尚未更新时进程被强杀，仍可能丢失该批展示增量。
- `post_likes` 仍保留真实关系，因此可通过周期性对账修正。
- 余额、库存等强一致数据不能照搬此方案。

### 2.6 可选演进

流量继续增大时，可考虑分段计数、按 postId 分区的日志流、CDC/流处理或专用计数服务。是否引入更重架构，应由准确性目标、峰值写入和恢复时间要求决定。

## 三、热门帖子详情缓存

### 3.1 为什么动静分离

帖子正文、图片和创建时间读多写少；点赞数和 `isLiked` 高频变化且带用户维度。缓存整份响应会让一次点赞触发大对象更新，也可能把 A 用户的 `isLiked` 返回给 B。

当前拆分为：

```text
公共静态详情：Caffeine → Redis → PostgreSQL
点赞数：DB 基准 + Redis delta
评论数：DB
isLiked：当前用户的 post_likes 关系
```

### 3.2 两级缓存职责

| 层 | 配置 | 作用 |
| --- | --- | --- |
| Caffeine | 50000 条、15 秒 TTL | 吸收单实例的瞬时热点，减少 Redis 网络请求 |
| Redis | 7 天 + 0～20 分钟抖动 | 多实例共享，保护 PostgreSQL |

Redis key 带版本：

```text
putao:post:detail:v1:<postId>
```

版本化便于未来结构不兼容时切换。

### 3.3 穿透、击穿与雪崩

不存在的帖子写入 `found=false`、TTL 30 秒的负缓存，降低无效 ID 对数据库的持续冲击。

两级缓存都未命中时，以 postId 为粒度争抢 Redisson 锁：

```text
miss
  → 尝试锁（最多等待 200ms，lease 5s）
  → 获锁后二次检查
  → 单个请求回源并回填
```

正常缓存 TTL 增加随机抖动，避免同批 key 同时过期。锁必须按帖子粒度，不能把整个详情服务串行化。

### 3.4 删除与多实例失效

数据库提交后：

```text
删除当前实例 Caffeine
  → DEL Redis
  → PUBLISH postId
  → 其他实例删除本地 Caffeine
```

Pub/Sub 不持久化，断线实例可能漏收；15 秒本地 TTL 限制了陈旧窗口。如果封禁或隐私变化要求接近零陈旧，需要可靠失效事件、版本号或读取时状态校验。

### 3.5 Redis 故障降级

缓存和分布式锁故障时允许回源 PostgreSQL，以避免缓存故障直接变成详情不可用。但生产环境必须同时配置数据库限流、超时、熔断和连接池保护，否则大量请求一起降级会形成缓存雪崩后的数据库雪崩。

## 四、推荐 Feed 三路混排

### 4.1 三个目标不能由一个排序池完成

- 热门池解决内容质量；
- 好友 timeline 解决社交相关性；
- 冷启动池解决新帖没有互动数据的问题。

只按时间会牺牲质量，只按热度会放大马太效应，只看好友会让关系稀疏用户缺少内容。

### 4.2 真正的位置混排

每 10 个位置：

```text
第 3 位：friend
第 6 位：cold_start
其余：recommend
```

实现必须在 Candidate 层按当前位置选择来源。若先连续 append 热门，再“插入”第 3、第 6 位，目标位置已经被占用，强插规则实际上不会生效。

指定来源为空时按：

```text
recommend → cold_start → friend
```

补位，避免好友能力缺失时整页不足。

### 4.3 Bloom 去重

```text
putao:user:read:bloom:<userId>
capacity = 5000
false positive = 1%
TTL = 7d
```

Bloom 适合“宁可偶尔少推荐一条，也不要保存精确已读全集”的场景。它可能把未读误判为已读，不能用于权限、计费等精确事实。

### 4.4 游标推进

Feed 游标记录热门和冷启动来源的 offset。offset 按“实际检查过的候选”推进，即使候选被 Bloom 过滤或帖子已经失效也要推进，否则下一页会反复扫描同一批脏数据。

当前 offset 游标适用于容量有限、后台整体重建的 Redis 池，但推荐池在翻页期间重建时仍可能出现轻微重复或漏项。更强稳定性可使用 score/member 复合游标或带版本的候选快照。

### 4.5 批量组装

候选 ID 选定后统一执行：

```text
批量 posts
批量 post_images
批量 post_stats
Redis MGET like delta
批量 post_likes
```

避免一页 N 条内容触发 N 组 DB/Redis 调用。

### 4.6 热门池重建

`FeedScoreJob` 每 5 分钟读取近 3 天正常帖子，结合 DB 统计与 Redis 点赞增量计算：

```text
(10 + likes + 3 × comments) / (hours + 2)^1.5
```

按作者性别分桶、各保留 Top 3000，先写临时 ZSet，再用 `RENAME` 原子替换正式池。空分桶直接删除正式池，避免对不存在的临时 key 执行 `RENAME`。

## 五、好友写扩散与 Transactional Outbox

### 5.1 写扩散为什么异步

发帖后把一个 postId 写入 N 个好友 timeline 是 O(N) 操作。同步执行会让发帖延迟取决于好友数，并把 user-service 或 Redis 抖动传递到主链路。

当前选择写扩散，是因为普通社交用户关系规模预期可控、Feed 读取频率高。timeline 只保存 postId，不复制帖子正文。

### 5.2 为什么不能“提交后直接发 MQ”

```text
DB COMMIT
  → 进程崩溃
  → MQ 未发送
```

帖子虽然存在，但好友永远收不到。先发 MQ 又会产生“消息成功、帖子回滚”的相反不一致。

创建帖子事务因此同时插入：

```text
post_fanout_outbox(status=PENDING)
```

只要本地事务提交，待扩散意图就不会静默丢失。

### 5.3 投递与重试

`PostFanoutOutboxJob` 每 5 秒扫描到期事件：

```text
PENDING
  → Producer 本地最多重试 3 次
  → 成功标记 DELIVERED
  → 失败 attempts + 1
  → next_retry_at 指数退避（最大 5 分钟）
```

`PostFanoutOutboxCleanupJob` 每小时分批删除 7 天前已投递事件。

### 5.4 至少一次与消费幂等

MQ 已发送、Outbox 尚未标记时进程崩溃，会再次发送。因此 Outbox 通常保证至少一次，不保证恰好一次。

Consumer 使用：

```text
ZADD putao:user:timeline:<userId> <createdAt> <postId>
```

同一个 postId 作为相同 member 重复写入只会覆盖，从存储语义上实现幂等。timeline 只保留最近 100 条，TTL 7 天。

### 5.5 当前未闭环点

`UserClient.getFriendUserIds()` 因 user-service 缺少正式好友列表能力而返回空集合。因此 Outbox、Producer、Consumer 和 timeline 写入架构存在，但真实好友 fanout 尚未闭环。文档和面试中必须明确这是“外部依赖未完成”，不能表述为线上已有好友 Feed。

### 5.6 大规模演进

大型账号不适合无条件写扩散。可采用：

```text
普通用户：写扩散
大 V：读扩散
中等账号：只向近期活跃用户写扩散
```

阈值应由好友分布、MQ 吞吐、Redis 写入、活跃度和 Feed 延迟压测决定。

## 六、楼中楼评论与一级评论窗口

### 6.1 关系建模

三个业务字段表达评论线程：

```text
一级评论：rootId=0, parentId=0
回复一级评论 A：rootId=A, parentId=A
回复线程内 B：rootId=A, parentId=B
replyToUserId：B 的作者
```

`rootId` 用于聚合同一线程的全部回复，`parentId` 表示直接回复对象。创建回复必须验证根、父和帖子属于同一线程。

### 6.2 为什么只缓存一级评论

评论列表接口的业务口径是一级评论。如果把回复也写进同一个 ZSet：

- 回复会占用最近 200 条窗口；
- Redis 路径与 DB 的 `root_id=0` 路径结果不同；
- 游标和 `hasMore` 会失真。

因此只有一级评论提交后进入：

```text
putao:post:comments:<postId>
```

窗口最多 200 条、TTL 7 天；不足时回源数据库。

### 6.3 评论计数为何不用点赞方案

评论频率通常低于点赞，且一条评论和 `comment_count` 有明确的一一对应关系。当前将：

```text
INSERT/DELETE comment
+ comment_count ±1
```

放在同一个 PostgreSQL 事务中。这样不需要评论 delta、dirty set 或 `CommentFlushJob`，一致性更简单。

### 6.4 删除边界

删除按业务 `comment_id` 定位并校验作者，不能使用内部自增 `id`。当前只逻辑删除目标评论并减少一次计数，没有级联处理整棵回复树；产品需要进一步定义“删除根评论后回复如何展示”。

## 七、游标分页与 N+1 消除

### 7.1 `pageSize + 1`

列表请求 N 条时查询 N+1 条：

```text
结果 ≤ N：hasMore=false
结果 = N+1：返回前 N 条，hasMore=true
nextCursor = 实际返回的第 N 条
```

游标不能取额外的第 N+1 条，否则它不会出现在当前页，也会被下一页条件跳过。

### 7.2 为什么不用大 offset

帖子和评论持续新增、删除。页码 offset 会随数据变化产生重复或漏读，且大 offset 扫描成本升高。雪花业务 ID 近似时间有序，适合：

```sql
WHERE post_id < :cursor
ORDER BY post_id DESC
LIMIT :pageSize + 1
```

### 7.3 批量读取模式

先获得一页主 ID，再分别批量读取关联数据并在内存按 ID 分组。循环中不得逐条访问数据库、Redis 或远程服务。批次过大时还要分片，避免超长 `IN` SQL、网络包或 Redis 命令。

## 八、设计原则总结

Post Service 没有让所有数据共用同一套一致性方案：

- 帖子、图片、初始统计、Outbox：本地事务；
- 评论和评论数：本地事务；
- 点赞关系：数据库幂等事实；
- 点赞展示数：Redis 写合并，最终一致；
- 公共详情：两级 Cache-Aside；
- 好友 timeline：Outbox + MQ，至少一次；
- Feed 已读：允许误判的 Bloom。

核心判断顺序是：先看数据价值和频率，再决定一致性、存储与异步方案，而不是先选技术组件。
