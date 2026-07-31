# Post Service 业务流程详解

> 本文是 Post Service 的主代码学习文档。事实来源为 `dating-server/post-service` 当前源码、`proto/post/post.proto`、数据库 migration 和相关配置。每个功能都按照代码真实执行顺序展开，使读者能从入口一路跟到数据库、Redis、MQ、定时任务和最终返回。

## 一、先认识统一调用结构

### 1.1 请求从哪里进入

Post Service 对外暴露 9 个 gRPC 方法，统一进入 `PostGrpcService`：

```text
CreatePost / GetPostDetail / ListUserPosts / DeletePost
ActionLike
CreateComment / ListComments / DeleteComment
GetRecommendFeed
```

请求到达业务方法前，`UserIdInterceptor` 从 metadata 的 `x-user-id` 读取用户 ID 并写入 gRPC `Context`。`PostGrpcService.extractUserId()` 再从 Context 取出：

```java
Long userId = UserIdInterceptor.USER_ID_CONTEXT_KEY.get();
```

如果上游没有传用户 ID，该方法返回 `null`。目前接口层没有统一拦截这个空值，而是交给后续业务或数据库约束处理，这是需要关注的鉴权边界。

### 1.2 各层职责

```text
PostGrpcService
  → 提取用户、基础参数校验、Proto 与 VO 转换

Service
  → 业务编排、事务边界、分支顺序

Manager
  → 单表访问、缓存、Redis 原子操作

Mapper / Redis / RocketMQ / user-service
  → 实际基础设施操作
```

阅读某个功能时，先看 Service 的顺序，再下钻 Manager。单看 Mapper 或 Redis 操作无法还原完整业务。

## 二、功能与后台流程总览

| 编号 | 功能/流程 | 主入口 | 最终影响 |
| --- | --- | --- | --- |
| F001 | 发布帖子 | `PostGrpcService.createPost` | 四张表、详情缓存、冷启动池、Outbox |
| F002 | 查看帖子详情 | `PostReadService.getPostDetail` | L1/L2/DB、动态计数、点赞关系 |
| F003 | 查看用户帖子列表 | `PostReadService.listUserPosts` | 游标查询与整页批量组装 |
| F004 | 删除帖子 | `PostWriteService.deletePost` | 逻辑删帖、删图片、缓存及冷启动池失效 |
| F005 | 点赞/取消点赞 | `LikeService.actionLike` | 点赞关系、Redis 点赞增量 |
| F006 | 创建评论/回复 | `CommentService.createComment` | 评论、评论数、一级评论窗口 |
| F007 | 查看一级评论 | `CommentService.listComments` | Redis 窗口或 DB 回源、游标结果 |
| F008 | 删除评论 | `CommentService.deleteComment` | 逻辑删除、评论数、评论窗口 |
| F009 | 推荐 Feed | `FeedService.getRecommendFeed` | 三路候选、Bloom、批量详情、游标 |
| F010 | 好友扩散投递 | `PostFanoutOutboxJob` | Outbox → RocketMQ → timeline |
| F011 | 点赞增量刷盘 | `LikeFlushJob.flushLikes` | Redis delta → `post_stats` |
| F012 | 热门池重建 | `FeedScoreJob.rebuildFeedPool` | 近三天帖子 → 男女热门池 |
| F013 | Outbox 清理 | `PostFanoutOutboxCleanupJob.cleanup` | 删除历史已投递事件 |

## 三、帖子生命周期

## F001 发布帖子

### 3.1 完整调用链

```text
PostGrpcService.createPost
  → PostWriteService.createPost             @Transactional
    → SnowflakeIdGenerator.nextId
    → PostManager.createPost                 INSERT posts
    → PostManager.saveImages                 INSERT post_images × N
    → PostManager.initStats                  INSERT post_stats
    → PostFanoutOutboxManager.enqueue        INSERT post_fanout_outbox
    → 注册 afterCommit
  → PostgreSQL COMMIT
  → PostDetailCacheManager.put               Caffeine + Redis
  → PostWriteService.addToColdStartPool      user-service/缓存 + Redis ZSet
  → gRPC 返回 postId
```

### 3.2 第一步：接口层校验

`PostGrpcService.createPost()` 按顺序处理：

1. 从 gRPC Context 提取 `userId`。
2. 读取正文和图片 key 列表。
3. 正文为 `null` 或 `trim()` 后为空，立即返回错误。
4. 原始正文长度超过 1024，立即返回错误。
5. 图片超过 9 张，立即返回错误。
6. 将正文 `trim()` 后交给 `PostWriteService`。

```java
if (content == null || content.trim().isEmpty()) return;
if (content.length() > 1024) return;
if (imageKeys != null && imageKeys.size() > 9) return;

long postId = postWriteService.createPost(
        userId, content.trim(), imageKeys);
```

注意：长度判断发生在 `trim()` 之前，所以“1024 个有效字符加首尾空格”会按原始长度判定超长。

### 3.3 第二步：进入真实事务方法

`PostWriteService.createPost()` 是被另一个 Spring Bean 直接调用的 public 方法，事务代理可以生效：

```java
@Transactional(rollbackFor = Exception.class)
public long createPost(...) { ... }
```

进入方法后：

1. 雪花算法生成业务 `postId`。
2. `imageKeys == null` 时转换为空列表，否则用 `List.copyOf()` 创建不可变副本。
3. 后续数据库和提交后回调都使用这个副本，避免调用方在执行过程中修改列表。

### 3.4 第三步：依次写入四类数据库记录

#### 3.4.1 帖子主记录

`PostManager.createPost()` 构造 `PostEntity`：

```text
post_id    = 雪花 ID
user_id    = 当前用户
content    = 已 trim 的正文
status     = NORMAL
deleted    = 0
created_at = now
updated_at = now
```

然后执行 `postMapper.insert(post)`。

#### 3.4.2 图片引用

`PostManager.saveImages()` 按客户端顺序逐张插入：

```java
for (int i = 0; i < imageKeys.size(); i++) {
    image.setPostId(postId);
    image.setSortOrder(i);
    image.setImageKey(imageKeys.get(i));
    postImageMapper.insert(image);
}
```

本服务只保存 key，不检查对象存储中的文件是否真的存在。

#### 3.4.3 初始统计

`PostManager.initStats()` 新建：

```text
post_id       = 当前帖子
like_count    = 0
comment_count = 0
```

以后点赞展示数以这条记录为 DB 基准，评论数直接更新它。

#### 3.4.4 Outbox 事件

`PostFanoutOutboxManager.enqueue()` 生成 UUID `eventId`，写入：

```text
post_id
author_user_id
created_at_epoch
status        = PENDING
attempts      = 0
next_retry_at = now
```

发帖主链路不直接发送 RocketMQ。Outbox 和帖子在同一事务中，保证“帖子成功就一定留下待扩散意图”。

### 3.5 第四步：数据库提交

上述任意 INSERT 抛异常，Spring 回滚整个事务：

```text
posts
post_images
post_stats
post_fanout_outbox
```

不会出现帖子存在但没有统计，或帖子存在却没有扩散事件的半成品。

### 3.6 第五步：提交后写缓存和冷启动池

事务内只注册 `TransactionSynchronization.afterCommit()`。真正 COMMIT 成功后才执行：

```java
postDetailCacheManager.put(post, safeImageKeys);
addToColdStartPool(postId, userId);
```

#### 详情缓存

`PostDetailCacheManager.put()`：

1. 构造只含公共字段的 `CacheValue`。
2. 写当前实例 Caffeine。
3. JSON 序列化后写 Redis。
4. Redis TTL 为 7 天加 0～20 分钟随机抖动。

Redis 写失败只记录警告；帖子已经成功，不回滚数据库。

#### 冷启动池

`addToColdStartPool()`：

1. `UserClient.isMale(userId)` 读取作者性别。
2. 按性别选择 male/female 冷启动 ZSet。
3. `ZADD`，member 是 postId，score 是当前 epoch second。
4. 如果超过 10000 条，从低分端裁剪最老内容。
5. 设置 TTL 7 天。

性别读取链路是 Caffeine → Redis → user-service；调用失败时 `UserClient` 返回 `false`，即按女性作者处理。冷启动 Redis 操作整体失败只记录警告，不影响发帖返回。

### 3.7 第六步：组装返回

Service 返回 `postId`，gRPC 层构造响应并完成流。Outbox 投递和好友 timeline 写入都不在本次请求等待范围内。

### 3.8 失败边界

| 失败位置 | 结果 |
| --- | --- |
| 四类 DB 写入任一步 | 全事务回滚，发帖失败 |
| 详情缓存写失败 | 发帖仍成功，后续读取回源 DB |
| 性别读取失败 | 降级到女性分桶 |
| 冷启动池写失败 | 发帖仍成功，但暂时没有冷启动曝光 |
| MQ 不可用 | Outbox 保持 PENDING，后台重试 |

## F002 查看帖子详情

### 3.9 完整调用链

```text
PostGrpcService.getPostDetail
  → PostReadService.getPostDetail
    → loadCore
      → PostDetailCacheManager.get
        → Caffeine
        → Redis
      → Redisson postId 锁
      → 等待后再次查缓存
      → loadCoreFromDatabase
        → posts
        → post_images
        → 正缓存或负缓存
    → assertVisible
    → PostStatManager.getCounts
    → PostLikeManager.isLiked
    → toDetail
  → Proto 响应
```

### 3.10 第一步：读取公共详情缓存

`PostDetailCacheManager.get(postId)`：

1. `postDetailLocalCache.getIfPresent(postId)` 查询 Caffeine。
2. 命中就直接返回，包括 `found=false` 的负缓存。
3. L1 未命中时读取 Redis `putao:post:detail:v1:<postId>`。
4. Redis 为空返回 `null`，表示真正 miss。
5. Redis 命中后反序列化 JSON，回填 Caffeine，再返回。
6. Redis 读取或反序列化异常只记录警告，并按 miss 处理。

缓存值只包含：

```text
found、postId、userId、content、status、imageKeys、createdAt
```

不包含点赞数、评论数和 `isLiked`。

### 3.11 第二步：处理命中和负缓存

`loadCore()` 拿到缓存值后：

```java
if (cached != null) {
    return requireFound(cached, postId);
}
```

- `found=true`：返回公共详情。
- `found=false`：立即抛 `PostNotFoundException`。
- `null`：进入热点回源控制。

### 3.12 第三步：热点 miss 单飞

缓存 miss 后：

1. 获取 `putao:lock:post:detail:<postId>` 对应的 Redisson `RLock`。
2. 最多等待 200ms，租约 5 秒。
3. 无论是否成功获得锁，等待结束后都再次查询 L1/L2。
4. 如果其他请求已经回填，直接使用缓存。
5. 二次检查仍 miss，执行 DB 回源。

```java
acquired = lock.tryLock(200, 5_000, MILLISECONDS);
cached = postDetailCacheManager.get(postId);
if (cached != null) return requireFound(cached, postId);
return loadCoreFromDatabase(postId);
```

这里不是绝对严格的单飞：未在 200ms 内拿到锁的请求，二次检查仍 miss 时也会回源 DB。设计目标是在常见热点下合并请求，同时避免无限等待。

Redisson 异常或线程被中断时也会直接回源 DB。finally 中只有当前线程确实持锁才解锁。

### 3.13 第四步：数据库回源与缓存回填

`loadCoreFromDatabase()`：

1. `PostManager.findByPostId()` 查询 `post_id` 且 `deleted=0` 的帖子。
2. 不存在时写 30 秒 `found=false` 负缓存。
3. 随后抛 `PostNotFoundException`。
4. 存在时按 `sort_order` 升序查询图片 key。
5. 写入 Caffeine 和 Redis 正缓存。
6. 返回内存中的 `CacheValue`。

### 3.14 第五步：可见性校验

公共详情拿到后才执行：

```java
boolean normal = core.status() == PostStatus.NORMAL;
boolean owner = currentUserId != null
        && currentUserId.equals(core.userId());
if (!normal && !owner) throw new PostNotFoundException(...);
```

因此：

- NORMAL：所有调用者可见。
- 非 NORMAL：仅作者可见。
- deleted=1：在 DB 查询阶段就当作不存在。

### 3.15 第六步：读取动态字段

`PostStatManager.getCounts()`：

1. `postStatMapper.selectById(postId)` 读取 DB 基准。
2. Redis GET 点赞增量。
3. `likeCount = baseLikes + delta`。
4. `commentCount = baseComments`。
5. Redis 失败则仅返回 DB 基准。

`PostLikeManager.isLiked()` 单独查询当前用户与帖子的点赞关系；未登录用户直接得到 `false`。

### 3.16 第七步：组装结果

`toDetail()` 把公共缓存字段、动态计数和用户点赞状态合并成 `PostDetailVO`，gRPC 层再转换为 Proto。

当前 gRPC 层把详情链路中的所有异常统一映射为 `NOT_FOUND`，即使异常实际来自 Redis 反序列化后的 DB 故障，也可能呈现为未找到，这是错误分类上的现状。

## F003 查看用户帖子列表

### 3.17 完整调用链

```text
PostGrpcService.listUserPosts
  → 默认 pageSize=20
  → PostReadService.listUserPosts
    → pageSize 限制到 1..50
    → PostManager.listByUserIdWithCursor(pageSize+1)
    → 判断 hasMore、截取当前页、计算 nextCursor
    → PostBatchReadManager.listImageKeys
    → PostBatchReadManager.getCounts
    → PostLikeManager.batchIsLiked
    → 按 posts 原顺序组装 VO
  → Proto 列表响应
```

### 3.18 第一步：参数归一化

接口层在未传正数时默认 20，Service 再执行：

```java
int pageSize = Math.max(1, Math.min(requestedPageSize, 50));
```

因此异常大页不会直接放大 DB 和 Redis 压力。

### 3.19 第二步：查询 `pageSize + 1`

首次请求不带有效 cursor：

```sql
WHERE user_id = ?
  AND deleted = 0
  AND status = NORMAL
ORDER BY post_id DESC
LIMIT pageSize + 1
```

后续页增加：

```sql
AND post_id < :cursor
```

雪花 ID 近似随时间递增，因此倒序 ID 游标能稳定加载更老记录。

### 3.20 第三步：计算分页结果

```java
hasMore = queried.size() > pageSize;
posts = queried.stream().limit(pageSize).toList();
nextCursor = hasMore
        ? posts.get(posts.size() - 1).getPostId()
        : 0L;
```

游标取实际返回页最后一条，而不是多查出来的额外记录，否则额外记录会被下一页条件跳过。

### 3.21 第四步：按整页批量读取

从当前页提取所有 postId 后：

1. 一次 `post_images IN (...)`，按 postId 和 `sort_order` 排序后分组。
2. 一次批量读取 `post_stats`。
3. 一次 Redis `MGET` 读取全部点赞 delta，逐项加到 DB 基准。
4. 一次 `post_likes IN (...)` 查询当前用户已点赞的帖子。

Redis MGET 失败时，`PostBatchReadManager` 保留 DB 基准计数，不让整个列表失败。

### 3.22 第五步：保持主列表顺序组装

最终以 `posts.stream()` 为主顺序，从三个 Map 中取图片、计数和点赞状态。缺失关联数据分别降级为空图片、`[0,0]` 和 `false`。

## F004 删除帖子

### 3.23 完整调用链

```text
PostGrpcService.deletePost
  → PostWriteService.deletePost              @Transactional
    → PostManager.getByPostId
    → 作者校验
    → PostManager.deletePost
      → UPDATE posts.deleted=1
      → DELETE post_images
    → 注册 afterCommit
  → PostgreSQL COMMIT
  → PostDetailCacheManager.evict
  → removeFromColdStartPools
  → 返回 success=true
```

### 3.24 逐步过程

1. `getByPostId()` 只查询 `deleted=0`，不存在或重复删除直接抛异常。
2. 比较 `post.userId` 和当前 `userId`，非作者抛 `ForbiddenException`。
3. 在事务内将 `posts.deleted` 改为 1，并更新时间。
4. 同一事务内物理删除 `post_images`。
5. 点赞、评论、统计、Outbox 和 timeline 引用不删除。
6. COMMIT 后清除当前实例 Caffeine。
7. 删除 Redis 详情 key。
8. 向详情失效频道发布 postId，其他实例收到后清本地缓存。
9. 从 male 和 female 两个冷启动池都执行 `ZREM`。

缓存或冷启动池删除失败只记录日志；数据库删除状态已经生效。Feed 批量详情读取会过滤 `deleted=1`，所以 timeline 中残留 ID 不会重新展示。

## 四、点赞与统计

## F005 点赞/取消点赞

### 4.1 完整调用链

```text
PostGrpcService.actionLike
  → action == LikeAction.LIKE 转 boolean
  → LikeService.actionLike
    → PostManager.getByPostId
    → PostLikeManager.upsertLike
      → PostLikeMapper.upsertLike
      → 若状态真实变化：PostStatManager.incrRedisLike
  → 返回 success=true
```

### 4.2 第一步：枚举转换

代码不是比较数字，而是：

```java
boolean like = request.getAction() == LikeAction.LIKE;
```

Proto 中 `LIKE=0`、`UNLIKE=1`，因此默认枚举值会被视为点赞。

### 4.3 第二步：帖子存在性

`PostManager.getByPostId(postId)` 查询未删除帖子。不存在时停止，不允许产生孤儿点赞关系。

### 4.4 第三步：数据库幂等写

Mapper 使用：

```sql
INSERT INTO post_likes(...)
VALUES(...)
ON CONFLICT(user_id, post_id)
DO UPDATE SET status = EXCLUDED.status
WHERE post_likes.status <> EXCLUDED.status
```

- 首次点赞、点赞改取消、取消改点赞：`affected > 0`。
- 重复提交相同状态：`affected = 0`。

只有真实变化才继续改变计数。

### 4.5 第四步：Redis 计数原子累加

`PostStatManager.incrRedisLike()` 用 Lua 一次执行：

```text
INCRBY like-delta ±1
EXPIRE like-delta 7天
SADD likeUpdatedSet postId
```

这样 Redis 内不会出现“delta 已增加但刷盘任务不知道”的中间状态。

### 4.6 第五步：返回

`LikeService` 无论状态是否变化都返回成功；重复请求只是 `changed=false`，不会重复计数。

### 4.7 当前一致性风险

点赞关系 DB 写和 Redis delta 不在同一事务：

```text
DB 状态更新成功
  → Redis 写失败
  → 本次接口报错
  → 客户端重试
  → DB 已是目标状态，affected=0
  → 不再补 Redis delta
```

这会造成展示计数永久少一次，直到基于 `post_likes` 对账。当前文档必须把 `post_likes` 视为事实来源，不能声称刷盘机制可以完全避免丢计数。

## F011 点赞增量刷盘

### 4.8 触发和互斥

`LikeFlushJob.flushLikes()` 使用 fixedDelay 60 秒，并由 ShedLock `post.likeFlush` 保证多实例互斥：

```text
lockAtMostFor = 2分钟
lockAtLeastFor = 5秒
batchSize     = 100
```

### 4.9 每批详细过程

1. 从 `putao:post:like:updated_set` 随机取最多 100 个不同 postId。
2. 集合为空则任务结束。
3. 对每个 postId 执行 Lua：

```lua
local v = GET delta
if not v then return 0 end
SET delta 0
return v
```

4. delta 非 0 时更新数据库：

```sql
like_count = GREATEST(0, like_count + delta)
```

5. 再执行一段 Lua 检查 delta。
6. 如果刷盘期间没有新点赞，delta 仍为 0，则 `SREM dirty`。
7. 如果出现新 delta，保留 dirty，下一轮继续处理。
8. 本批正好 100 个时继续下一批；不足 100 个时结束循环。

### 4.10 DB 更新失败

catch 分支：

1. 将刚刚清零取出的 delta 加回 Redis。
2. 再次把 postId 加回 dirty set。
3. 记录错误，等待下轮。

但 Redis 清零后、进入 catch 前进程被强杀，无法执行补回，仍有极小丢失窗口。

## 五、评论

## F006 创建评论或回复

### 5.1 完整调用链

```text
PostGrpcService.createComment
  → 内容校验和 trim
  → CommentService.createComment             @Transactional
    → PostManager.getByPostId
    → 归一化 rootId/parentId
    → 校验根评论
    → 校验父评论和线程
    → PostCommentManager.createComment
    → PostStatManager.incrementCommentCount(+1)
    → 注册 afterCommit
  → PostgreSQL COMMIT
  → PostCommentManager.cacheRootComment
  → 返回 commentId
```

### 5.2 第一步：内容校验

接口层拒绝空白内容和超过 512 字符的原始内容，再把 `trim()` 后的正文传入 Service。

### 5.3 第二步：帖子校验和 ID 归一化

Service 先确认帖子未删除，然后：

```java
normalizedRootId = rootId == null ? 0 : rootId;
normalizedParentId = parentId == null ? 0 : parentId;
replyToUserId = 0;
```

虽然 Proto 标量默认值通常已经是 0，Service 仍保留 null 防御。

### 5.4 第三步：区分一级评论和回复

#### 一级评论

```text
rootId=0
parentId 必须=0
replyToUserId=0
```

`rootId=0` 但 `parentId!=0` 会直接抛参数异常。

#### 回复

`rootId!=0` 时：

1. 按业务 `comment_id` 查询根评论，且要求 `deleted=0`。
2. 根评论必须属于当前 postId。
3. 根评论自身的 `rootId` 必须为 0，防止把回复误当根。
4. `parentId=0` 时，把根评论本身作为父评论。
5. `parentId!=0` 时按业务 ID 查询父评论。
6. 父评论必须是根评论本身，或 `parent.rootId == normalizedRootId`。
7. 父评论也必须属于当前帖子。
8. 最终把 `parentId` 规范为真实父评论 ID。
9. `replyToUserId` 取父评论作者。

### 5.5 第四步：写评论和评论数

`PostCommentManager.createComment()`：

1. 生成雪花 `commentId`。
2. 写 postId、userId、rootId、parentId、replyToUserId、正文。
3. 设置 `status=1`、`deleted=0`、创建时间。
4. INSERT `post_comments`。

随后 `PostStatManager.incrementCommentCount(postId, 1)` 更新 `post_stats`。

两步在同一 PostgreSQL 事务：评论插入失败或计数更新失败都会一起回滚。

### 5.6 第五步：提交后维护一级评论窗口

COMMIT 后调用 `cacheRootComment(comment)`：

- `rootId!=0` 的回复直接返回，不写窗口。
- 一级评论 `ZADD putao:post:comments:<postId>`。
- member 和 score 都是 commentId。
- 超过 200 条时裁剪低分端最老记录。
- TTL 设为 7 天。

缓存失败被 catch 并记录警告，不影响已经提交的评论。

## F007 查看一级评论

### 5.7 完整调用链

```text
PostGrpcService.listComments
  → 默认 pageSize=20
  → CommentService.listComments
    → clamp 1..50
    → PostCommentManager.listComments(pageSize+1)
      → Redis ZSet 取 ID
      → 足量：DB 批量取详情并恢复 ZSet 顺序
      → 不足：DB 游标查询
    → hasMore / nextCursor
    → CommentVO 列表
  → Proto 响应
```

### 5.8 第一步：Redis 取候选 ID

首次分页：

```text
ZREVRANGE key 0 limit-1
```

后续分页：

```text
ZREVRANGEBYSCORE key 0 cursor-1 LIMIT 0 limit
```

这里的 `limit` 已经是 `pageSize+1`。

### 5.9 第二步：判断是否回源

- Redis ID 数量达到 limit：按这些 ID 批量查询 DB。
- Redis ID 不足 limit：直接按 postId、`root_id=0`、`deleted=0` 从 DB 查询。

Redis 足量时仍需回 DB 取评论正文，ZSet 只缓存 ID。批量结果先映射为 `commentId → entity`，再按 Redis ID 顺序恢复。

需要注意两个实际边界：

1. Redis 操作没有 try/catch；Redis 故障会使接口失败，不会自动降级 DB。
2. Redis ID 足量但其中部分评论已经删除时，过滤后可能不足一页，当前代码不会再次回源补齐。

### 5.10 第三步：分页计算

与帖子列表相同：

```text
queried.size > pageSize → hasMore=true
当前页 = 前 pageSize 条
nextCursor = 当前页最后一条 commentId
```

没有更多时 `nextCursor=0`。

### 5.11 第四步：VO 与 Proto

每条评论返回：

```text
commentId、postId、userId
rootId、parentId、replyToUserId
content、createdAt
```

当前公开方法只列一级评论；代码中没有单独的“按 rootId 查看全部回复”公开 RPC。

## F008 删除评论

### 5.12 完整调用链和过程

```text
PostGrpcService.deleteComment
  → CommentService.deleteComment              @Transactional
    → PostCommentManager.getByCommentId
    → 作者校验
    → UPDATE post_comments.deleted=1
    → UPDATE post_stats.comment_count-1
    → 注册 afterCommit
  → PostgreSQL COMMIT
  → ZREM 一级评论窗口
  → 返回 success=true
```

详细步骤：

1. 按业务 `comment_id` 和 `deleted=0` 查询，不能使用内部 bigserial `id`。
2. 当前用户不是评论作者时抛 `ForbiddenException`。
3. 逻辑删除评论。
4. 同一事务内将评论数减 1，Mapper 负责避免结果小于 0。
5. COMMIT 后从该帖评论 ZSet 移除 commentId。
6. Redis 删除失败只记录警告。

当前不会级联删除回复，也不会按整棵线程调整计数。删除根评论后回复如何展示仍是未补齐的产品与实现边界。

## 六、推荐 Feed

## F009 获取推荐 Feed

### 6.1 完整调用链

```text
PostGrpcService.getRecommendFeed
  → 默认 pageSize=20
  → FeedService.getRecommendFeed
    → clamp pageSize、解析 cursor
    → UserClient.isMale
    → 拉热门/好友/冷启动三路候选
    → 初始化或获取用户 Bloom
    → selectCandidates 位置混排
    → loadDetails 批量详情
    → Bloom/失效过滤并推进游标
    → 计算 hasMore、nextCursor
  → Proto Feed
```

### 6.2 第一步：分页和性别

1. 页大小限制 1～50。
2. cursor 格式是 `<recommendOffset>:<coldStartOffset>`。
3. 空、`0`、`0:0` 或解析异常都回到 `[0,0]`。
4. `UserClient.isMale(currentUserId)` 获取当前用户性别。
5. 男用户选择 female 池，非男用户选择 male 池。

性别读取失败返回 `false`，因此会选择 male 内容池。

### 6.3 第二步：拉取三路原始候选

`fetchSize = pageSize * 3`：

```text
recommend：从 recOffset 开始取 fetchSize
friend：始终从 timeline 第 0 条开始取 pageSize
cold_start：从 coldOffset 开始取 fetchSize
```

三路当前是顺序执行 Redis `ZREVRANGE`，不是并行 Future。

### 6.4 第三步：Bloom 初始化

获取：

```text
putao:user:read:bloom:<userId>
capacity=5000
falsePositive=1%
TTL=7天
```

首次创建或发现没有 TTL 时重新设置 7 天过期。

### 6.5 第四步：按位置选择候选

最多先选 `pageSize*2` 个 Candidate，为后续 Bloom 和失效过滤留余量。

每轮根据当前已选结果数计算 1～10 的 slot：

```text
slot=3 → FRIEND
slot=6 → COLD_START
其他   → RECOMMEND
```

首选来源为空时依次尝试 recommend、cold_start、friend。`selected` Set 保证三路出现同一 postId 时只保留一次。

Candidate 同时保留 postId 和实际来源，后续可统计来源并正确推进对应 offset。

### 6.6 第五步：一次批量装载候选详情

`loadDetails()` 先按出现顺序去重，然后：

1. `PostManager.listByPostIds()` 批量取未删除帖子。
2. 批量取图片并按 postId 分组。
3. 批量取 DB 统计。
4. Redis MGET 全部点赞 delta。
5. 批量取当前用户点赞关系。
6. 内存过滤 `status != NORMAL` 的帖子。
7. 组装为 `postId → PostDetailVO`。

这一步把候选循环中的 N+1 查询变成固定批量访问。

### 6.7 第六步：过滤、计数和游标推进

按 Candidate 顺序逐个检查：

1. 如果来源是 recommend，`recExamined++`。
2. 如果来源是 cold_start，`coldExamined++`。
3. Bloom 已存在：跳过。
4. 批量详情中没有该 postId：说明已删、异常或非正常状态，跳过。
5. 有效时加入 items。
6. 将 postId 写入 Bloom。
7. 对实际来源计数。
8. items 达到 pageSize 就停止。

offset 按“已经检查的候选”推进，而不是按成功返回条数推进，因此失效或已读内容不会在下一页再次被扫描。

好友来源没有独立 cursor，每页仍从 timeline 顶部读取，依赖 Bloom 去重。

### 6.8 第七步：返回游标

```java
hasMore = !items.isEmpty()
        && (items.size() == pageSize
            || candidates.size() > items.size());
```

有更多时：

```text
nextCursor =
  (recOffset + recExamined)
  :
  (coldOffset + coldExamined)
```

这是启发式 `hasMore`，并不查询三路池的精确剩余数量。

## F012 热门池重建

### 6.9 调度入口

`FeedScoreJob` 每 5 分钟 fixedRate 触发，ShedLock：

```text
name=post.feedScore
lockAtMostFor=10分钟
lockAtLeastFor=1分钟
```

Job 只负责计时、日志和异常兜底，实际流程在 `FeedService.rebuildRecommendPool()`。

### 6.10 逐步重建

1. 查询近 3 天正常帖子；为空则直接结束，当前不会主动清空旧池。
2. 收集所有 postId。
3. 批量读取 `post_stats` 和 Redis 点赞 delta。
4. 收集并去重所有作者 userId。
5. `UserClient.getGenders()` 先逐个查 Redis，miss 集合再批量 RPC。
6. RPC 失败或某个用户无结果时默认 `false`，进入 female 作者桶。
7. 对每个帖子计算小时年龄。
8. 计算热度：

```text
(10 + likes + 3 * comments) / (hours + 2)^1.5
```

9. 按作者性别放入 maleScores 或 femaleScores。
10. 两桶分别按 score 降序排序。
11. 各截取 Top 3000。
12. 调用 `replacePool()` 替换 Redis 正式池。

### 6.11 影子池替换

`replacePool(tempKey, finalKey, entries)`：

1. 先删除旧 temp key。
2. 如果新桶为空，删除 final key 后返回。
3. 批量 ZADD 所有 `(postId, score)` 到 temp。
4. temp 设置 7 天 TTL。
5. `RENAME temp → final` 原子切换。
6. final 再设置 7 天 TTL。

Job 捕获整个重建异常并记录日志，下一个调度周期再尝试。

## 七、好友写扩散

## F010 Outbox 投递与 MQ 消费

### 7.1 Outbox Job 触发

`PostFanoutOutboxJob.deliver()` 每次执行完成后延迟 5 秒再次运行，并用 ShedLock：

```text
name=post.fanoutOutbox
lockAtMostFor=30秒
lockAtLeastFor=1秒
```

### 7.2 扫描和发送

`PostFanoutOutboxService.deliverDue()`：

1. 查询最多 100 条 `PENDING` 且 `next_retry_at <= now` 的事件。
2. 逐条调用 Producer，传 eventId、postId、authorUserId、createdAtEpoch。
3. Producer 构造 `FanoutMessage`。
4. 对同一事件最多同步发送 3 次。
5. 每次超时 2 秒。
6. 收到 `SEND_OK` 立即返回 true。
7. 三次全失败记录 `post.fanout.produce.fail` 指标并返回 false。

### 7.3 更新 Outbox 状态

- 发送成功：`markDelivered(id)`。
- 发送失败：attempts 加 1，并计算下次时间。

退避：

```java
delaySeconds = min(300, 1 << min(attempts, 8));
```

因此延迟按约 2、4、8 秒增长，最大 300 秒。

发送成功但标记 DELIVERED 前进程崩溃时，事件会再次发送，所以只能保证至少一次。

### 7.4 Consumer 逐步处理

RocketMQ Consumer：

```text
topic            = youjianxin-dating-dev-post-fanout-v1
consumerGroup    = youjianxin-dating-dev-post-service-fanout
maxReconsumeTimes= 16
```

收到消息后：

1. 调用 `UserClient.getFriendUserIds(authorUserId)`。
2. 当前方法因 user.proto 没有好友列表 RPC，直接返回空列表。
3. 空列表时记录 debug 并结束，因此当前不会写入真实好友 timeline。
4. 将来好友列表接通后，对每个 follower 调用 `writeToTimeline()`。
5. `ZADD`，score 为发帖 epoch，member 为 postId。
6. 超过 100 条时删除最老记录。
7. 设置 TTL 7 天。
8. 任一 follower 写失败都会重新抛异常，触发 RocketMQ 重投。

同一 postId 作为 ZSet member 重投只会覆盖，消费天然幂等。

## F013 Outbox 历史清理

`PostFanoutOutboxCleanupJob.cleanup()`：

1. fixedDelay 每小时执行。
2. ShedLock `post.fanoutOutboxCleanup` 保证多实例互斥。
3. 每次调用 Mapper 删除最多 10000 条 7 天前且状态为 DELIVERED 的记录。
4. 只清理已投递历史，不删除 PENDING 重试事件。
5. 本批还有更多数据时等待下一个小时，不在单次任务中无限循环。

## 八、共享的用户资料读取

### 8.1 单用户性别

`UserClient.isMale()` 外层有 `@Cacheable("userGender")`：

```text
Caffeine 命中
  → 返回
miss
  → Redis GET putao:post:user:gender:<userId>
命中
  → "1"=male，其他=female
miss
  → user-service GetProfile
  → Redis 缓存 6 小时
```

RPC 或 Redis 访问整体异常时返回 `false`。

### 8.2 批量性别

热门池重建使用 `getGenders()`：

1. 每个 userId 先查 Redis。
2. 收集 misses。
3. 对 misses 发一次 `batchGetProfile`。
4. 将响应写入结果 Map 和 Redis。
5. RPC 失败仅记录警告。
6. 最终所有缺失用户都 `putIfAbsent(false)`。

这避免每个帖子单独发一次 RPC，但 Redis 当前仍是逐用户 GET，不是 MGET。

## 九、数据与异步关系总图

```text
发帖事务
  ├─ posts
  ├─ post_images
  ├─ post_stats
  └─ post_fanout_outbox
       └─ OutboxJob → RocketMQ → Consumer → user timeline

发帖提交后
  ├─ Caffeine/Redis 公共详情
  └─ cold_start ZSet

点赞
  ├─ post_likes（事实）
  └─ Redis like delta
       └─ LikeFlushJob → post_stats.like_count

评论事务
  ├─ post_comments
  └─ post_stats.comment_count
       └─ 提交后维护一级评论 ZSet

FeedScoreJob
  └─ 近3天 posts + stats/delta + user gender
       └─ recommend male/female ZSet
```

## 十、实现状态和关键风险

| 项目 | 当前状态 | 代码层影响 |
| --- | --- | --- |
| 好友列表 | 桩实现 | Outbox/MQ 完成，但 Consumer 得不到 follower |
| 点赞 DB/Redis 双写 | 非原子 | DB 成功 Redis 失败时需要对账修复 |
| 点赞刷盘 | 最终一致 | Redis 清零后进程崩溃有小窗口 |
| 评论 Redis 故障 | 未降级 | `listComments` 会失败，不会自动回 DB |
| 评论树删除 | 未实现级联 | 删除根评论不会删除回复 |
| 回复列表 | 无公开 RPC | 数据模型支持回复，公开列表只查一级评论 |
| Feed 好友分页 | 无独立游标 | 每页从 timeline 顶部取，依赖 Bloom |
| Feed `hasMore` | 启发式 | 不代表三路池精确剩余量 |
| 用户身份缺失 | 入口未统一拒绝 | `extractUserId()` 可能返回 null |
| gRPC 异常映射 | 较粗 | 多种内部异常可能被统一成 INTERNAL/NOT_FOUND |

## 十一、代码索引

| 主题 | 核心类 |
| --- | --- |
| gRPC 入口 | `PostGrpcService`、`UserIdInterceptor` |
| 发帖/删帖事务 | `PostWriteService`、`PostManager` |
| 详情读取 | `PostReadService`、`PostDetailCacheManager` |
| 列表批量组装 | `PostBatchReadManager` |
| 点赞关系/增量 | `LikeService`、`PostLikeManager`、`PostStatManager` |
| 点赞刷盘 | `LikeFlushJob` |
| 评论 | `CommentService`、`PostCommentManager` |
| Feed | `FeedService`、`FeedScoreJob` |
| Outbox | `PostFanoutOutboxManager`、`PostFanoutOutboxService`、两个 Outbox Job |
| MQ | `PostFanoutProducer`、`PostFanoutConsumer` |
| 用户依赖 | `UserClient`、`GrpcClientConfig` |
| Redis key | `RedisKey` |

## 十二、阅读代码的建议顺序

学习一个功能时按下面顺序打开文件：

```text
1. PostGrpcService：确认入口参数和错误映射
2. 对应 Service：掌握完整顺序和事务
3. Manager：确认 SQL/Redis 的真实行为
4. Mapper 或 migration：核对约束和更新条件
5. Job/Producer/Consumer：补齐异步后半程
6. VO/Proto：确认最终输出
```

这样不会只看到某个 Redis 或 SQL 片段，却不知道它在事务前、事务内还是提交后执行。
