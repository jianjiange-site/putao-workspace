# Post-Service 源码目录导读

> 学习日期：2026-07-19
> 学习范围：`dating-server/post-service/src/main/java/com/dating/post/` 下各个子目录的职责与组织方式
> 目标：看懂这个服务是怎么按"经典阿里分层 + 业务扩展"组织的

## 阅读起点

打开 `PostApplication.java`（19 行），先理解四个开关：

```java
@SpringBootApplication    // Spring Boot 启动
@EnableDiscoveryClient    // 注册到 Nacos
@EnableScheduling         // 开启 @Scheduled 定时任务
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M") // ShedLock 多实例互斥
@MapperScan("com.dating.post.mapper")              // MyBatis-Plus 扫包
```

入口本身什么都不做，所有能力通过包扫描 + 配置类加载。下面是 13 个子目录的分工。

---

## 目录分工全景图

```
com.dating.post/
├── PostApplication.java        # 启动入口
│
├── grpc/                      # 🟢 对外：gRPC 暴露 9 个 RPC 接口
│
├── client/                    # 🟢 对外：调用别的服务（user-service）
│
├── service/                   # 🟡 业务编排：5 个 Service，发帖/点赞/评论/读/Feed
├── manager/                   # 🟡 数据编排：4 个 Manager，包装 mapper + 缓存 + 增量
│
├── mapper/                    # 🔵 持久层：5 张表 × 5 个 Mapper（一一对应）
├── entity/                    # 🔵 数据库实体：5 张表的 Java 映射
│
├── constant/                  # 🟣 静态常量：Redis Key、状态码、状态枚举
├── vo/                        # 🟣 出参对象：返回给上层的 VO
│
├── job/                       # 🔴 定时任务：3 个 Job（点赞刷盘、评论刷盘、Feed 重建）
├── mq/                        # 🔴 异步消息：producer + consumer（写扩散）
│
├── config/                    # 🟤 配置类：8 个 @Configuration
├── exception/                 # 🟤 业务异常 + gRPC 异常处理
```

**调用方向**（与 `.cursorrules` 一致）：
```
grpc / mq-consumer
    ↓
service   ← 业务编排、事务边界
    ↓
manager   ← 数据访问编排、缓存
    ↓
mapper    ← 单表 SQL
    ↓
DB / Redis
```

---

## 1. `grpc/` — 对外的 9 个 gRPC 接口门面

| 文件 | 作用 |
|------|------|
| `PostGrpcService.java` | 继承 `PostServiceImplBase`，实现 9 个 RPC（createPost / getPostDetail / listUserPosts / actionLike / createComment / listComments / deleteComment / deletePost / getRecommendFeed）。**只做：① 从 Context 取 userId ② 调对应 Service ③ 错误码映射** |
| `UserIdInterceptor.java` | 全局服务端拦截器。从 gRPC metadata 的 `x-user-id` 头里读出 userId（mobile-gateway 解 JWT 后注入的），塞进 gRPC `Context`，业务代码用 `Context.current().get(USER_ID_CONTEXT_KEY)` 取 |

**学习要点**：grpc 层就是门面层，**没有任何业务逻辑**。所有判断都委托给 service。这是分层最重要的边界。

---

## 2. `client/` — 调用 user-service 的 gRPC Stub

只有一个文件 `UserClient.java`，封装了三个方法：

| 方法 | 用途 | 失败降级 |
|------|------|----------|
| `getFriendUserIds(userId)` | 发帖时拉好友做写扩散 | 返回空列表 |
| `isMale(userId)` | Feed 推荐时性别分桶 | 走 Caffeine 30 秒缓存；挂掉默认 false |
| `getGenders(userIds)` | 重建 Feed 池时批量查性别，避免 RPC 风暴 | 全部默认 false |
| `getUserProfiles(userIds)` | 批量拿 Feed 中作者的资料 | 返回空列表 |

**学习要点**：
1. 跨服务**只用 gRPC**，调用方断路降级，不阻塞主流程。
2. `@Cacheable("userGender")` + Caffeine 做的是**本地短 TTL 缓存**（不持久化到 Redis，30 秒过期），专门防 `FeedScoreJob` 每 5 分钟跑时把 user-service 打爆。

> 注：当前标注 `TODO: 等 user-service 实现…后替换`，暂时是桩实现（用 `userId % 2 == 0` 判性别），但 gRPC 通道、Caffeine 缓存、降级策略都是完整的。

---

## 3. `service/` — 5 个业务编排器

| 文件 | 职责 | 关键 |
|------|------|------|
| `PostWriteService.java` | 发帖、删帖 | 唯一带 `@Transactional` 的写服务；调用雪花 ID → 写库 → 缓存 → 入冷启动池 → 发 MQ |
| `PostReadService.java` | 读帖子详情、读某人帖子列表 | 协调 `PostManager` + `PostStatManager` + `PostLikeManager` 拼装详情 |
| `LikeService.java` | 点赞/取消点赞 | **只** 委托给 `PostLikeManager` |
| `CommentService.java` | 评论增删查 | 同上 |
| `FeedService.java` | 三路混合推荐 | 这是 post-service **最复杂** 的文件，重点看下面 |

### `FeedService` 是核心

阅读时盯住 3 个数据来源 + 1 个过滤器：

| 角色 | 来源 | Redis Key |
|------|------|-----------|
| 推荐位（8/10 个 slot） | `ZSet<postId,score>`（按热度打分） | `putao:feed:pool:recommend:male/female` |
| 好友时间线（1 个 slot） | `ZSet<postId,score>`（按关注写入） | `putao:user:timeline:<userId>` |
| 冷启动池（1 个 slot） | `ZSet<postId,score>`（新帖扶持） | `putao:feed:cold_start:pool:male/female` |
| 已读过滤器 | Redisson 布隆过滤器 | `putao:user:read:bloom:<userId>` |

读 `rebuildRecommendPool()` 看打分流：
- 数据库拉**近 3 天**所有 `status=1` 帖子
- 批量算分（HN 变体 `score = (10 + likes + 3*comments) / (hoursDiff + 2)^1.5`）
- 性别分桶，各取 Top 3000
- 写**影子 ZSet + 原子 RENAME** 替换（旧集合原子切，新集合 7 天 TTL）

---

## 4. `manager/` — 4 个数据访问编排器

Manager 层是 service 与 mapper 之间的粘合剂。每个 Manager 对应**一组**相关表 + Redis 缓存。

| 文件 | 包哪些 mapper | 包哪些 Redis | 关键能力 |
|------|--------------|---------------|----------|
| `PostManager.java` | PostMapper、PostImageMapper、PostStatMapper | `post:detail:<id>` Hash | 单帖子读写 + 帖子详情缓存读写删 |
| `PostStatManager.java` | PostStatMapper | `post:stat:incr:<id>:likes/comments` | 计数 = `DB基准 + Redis增量`，3 个增量方法 |
| `PostLikeManager.java` | PostLikeMapper | 同上 + `post:updated_set` | 幂等 upsert（先查后写）、`changed` 标记待刷盘 |
| `PostCommentManager.java` | PostCommentMapper | `post:comments:<id>` ZSet + 同上 | 评论 ZSet 窗口（最近 200 条）、多级评论（rootId/parentId） |

### Manager 是"Redis 写合并"的设计支点

点赞/评论的真实落库流程是双层：

```
入口  LikeService.actionLike
        ↓
   PostLikeManager.upsertLike  ←——①查状态 ②upsert ③Redis INCR + updated_set.add(postId)
        ↓
每分钟  LikeFlushJob.flushLikes  ←——①SRANDMEMBER 100 ②Lua GET+SET0 ③UPDATE post_stats ④SREM
```

**好处**：高并发点赞不直接打 DB，先攒到 Redis；定时任务批量刷盘，PG 单行锁的争用从 N 降到 floor(N/100)。

详细代码：
```32:36:dating-server/post-service/src/main/java/com/dating/post/service/LikeService.java
    public boolean actionLike(Long userId, Long postId, boolean like) {
```

```37:63:dating-server/post-service/src/main/java/com/dating/post/manager/PostLikeManager.java
    public boolean upsertLike(Long userId, Long postId, boolean like) {
```

```50:92:dating-server/post-service/src/main/java/com/dating/post/job/LikeFlushJob.java
    public void flushLikes() {
```

---

## 5. `mapper/` + `entity/` — 一张表一对，严格 1:1

```
mapper/PostMapper.java      ← → entity/PostEntity.java       (posts 表)
mapper/PostImageMapper.java ← → entity/PostImageEntity.java  (post_images 表)
mapper/PostLikeMapper.java  ← → entity/PostLikeEntity.java   (post_likes 表)
mapper/PostCommentMapper.java ← → entity/PostCommentEntity.java (post_comments 表)
mapper/PostStatMapper.java  ← → entity/PostStatEntity.java   (post_stats 表)
```

**红线**：项目规则是**单表操作**、**禁止 JOIN**。从代码里也能验证：
- 每个 Mapper 都只 `extends BaseMapper<自己的Entity>`
- 多表"关联查询"用`in (...)`拆成两步：`listByUserIds` → `selectList(postIds)`
- 列表/计数都靠**应用层拼装**（见 `PostReadService.getPostDetail`）

`@TableField(fill = FieldFill.INSERT)` 自动填充 `createdAt` / `updatedAt`，对应 MyBatis-Plus 的 MetaObjectHandler。

---

## 6. `constant/` + `vo/` — 常量与出参

### `constant/`
- `RedisKey.java` — 所有 Redis Key 的模板方法。前缀通过 `setPrefix(...)` 从 `app.cache.key-prefix` 注入。所有 Key 命名遵循 `putao:<service>:<domain>:<id>` 规则
- `ErrorCode.java` — gRPC 层用到的错误码（如 `CONTENT_EMPTY`、`IMAGE_COUNT_EXCEEDED`）
- `PostStatus.java` — 帖子状态枚举（NORMAL / DELETED / AUDITING）
- `LikeStatus.java` — 点赞状态枚举（LIKED / UNLIKED）

### `vo/`
5 个返回对象：`PostDetailVO`、`CommentsVO`、`CommentVO`、`RecommendFeedVO`、`UserPostsVO`。

命名严格：`Req`/`Resp`/`DTO` / `VO` 对应入参出参。**对外只回业务主键 `post_id`，不回内部自增 `id`**（这是红线 #9）。

---

## 7. `job/` + `mq/` — 异步/定时

### 定时任务（`@Scheduled + ShedLock`）

| Job | 频率 | 干什么 |
|-----|------|--------|
| `LikeFlushJob` | 60 秒 | Redis 点赞增量 → PG `post_stats` |
| `CommentFlushJob` | 60 秒 | Redis 评论增量 → PG `post_stats` |
| `FeedScoreJob` | 5 分钟 | 三路打分 → 写热门池 ZSet + 影子 RENAME |

所有 Job 都带 `@SchedulerLock(...)`，避免多实例同时跑。`lockAtMostFor = "PT2M"` 是兜底超时（防止某次死锁永远占着锁）。

### 消息队列（写扩散）

```
PostWriteService.createPost (事务内)
   ↓ 同步事务外
PostFanoutProducer.send (MQ send retry ×3, 失败仅计数)
   ↓ RocketMQ
PostFanoutConsumer.onMessage (CONCURRENTLY, maxReconsumeTimes=16)
   ↓
拉关注者列表 (UserClient.getFriendUserIds)
   ↓
写每个 follower 的 Timeline ZSet (ZADD + 裁剪到 100 + 7天TTL)
```

Topic = `youjianxin-dating-dev-post-fanout-v1`，消费者组 = `*-post-service-fanout`。

**设计取舍**：写扩散**不影响发帖事务**。MQ 失败也允许，丢一次最多让某些粉丝少看到一条帖子；不会因为 MQ 慢而阻塞用户。失败用 Micrometer 指标计数（`post.fanout.produce.fail`）打点，方便告警。

---

## 8. `config/` + `exception/` — 横切关注点

### `config/` 的 8 个文件
| 名字 | 一句话 |
|------|--------|
| `AppInitConfig.java` | 启动期注入 `RedisKey.setPrefix(...)` |
| `RedisConfig.java` | StringRedisTemplate / RedisTemplate |
| `RedissonConfig.java` | RedissonClient（FeedService 用布隆过滤器） |
| `CaffeineConfig.java` | 本地缓存（UserClient 的 userGender） |
| `GrpcClientConfig.java` | gRPC Stub 客户端（当前 gRPC Channel 是手动建的，将来可换） |
| `ShedLockConfig.java` | ShedLock 用 JDBC 提供 LockProvider |
| `SnowflakeIdConfig.java` | 雪花 ID 生成器（业务主键不暴露自增） |
| `EnvDiagnosticsEnvironmentPostProcessor.java` | 环境诊断，启动早期打 nacos/redis 连通性日志，定位 NPE |

### `exception/`
- `BizException.java` — 业务异常基类
- `PostNotFoundException` / `CommentNotFoundException` / `ForbiddenException` — 具体业务异常
- `GlobalExceptionHandler.java` — HTTP `@RestControllerAdvice` 兜底（虽然这个服务主要靠 gRPC，但兜底保留）
- `PostGrpcExceptionHandler.java` — gRPC 异常处理

---

## 阅读顺序建议（按学习目标分路）

### 路 A：想懂"高并发计数怎么写"
1. `service/LikeService.java`
2. `manager/PostLikeManager.java` — 注意 `upsertLike` 的幂等 + `incrRedisLike` + `updated_set.add`
3. `manager/PostStatManager.java` — 注意 `getCounts = base + Redis增量`
4. `job/LikeFlushJob.java` — 注意 Lua 原子 GET+SET0

### 路 B：想懂"Feed 推荐怎么搭"
1. `service/FeedService.java` — 通读 `getRecommendFeed` 整个方法
2. 看 `constant/RedisKey.java` 的 feed 池相关 key
3. `job/FeedScoreJob.java` → 回到 `FeedService.rebuildRecommendPool`
4. 用 `UserClient.isMale` 把性别分桶串起来
5. 复习 `manager/PostStatManager.batchGetBaseCounts`（批量计数的优化）

### 路 C：想懂"写扩散异步链路"
1. `service/PostWriteService.createPost` 最后两步（冷启动池 + 发 MQ）
2. `mq/producer/PostFanoutProducer.java`
3. `mq/consumer/PostFanoutConsumer.java` → Timeline ZSet 写入策略
4. 看 `constant/RedisKey.userTimeline()` 的语义

### 路 D：想懂"分层为什么这样切"
1. 任意挑一个 RPC（如 `actionLike`），从 `grpc/PostGrpcService` 一路追到 `mapper/PostLikeMapper`
2. 验证"调用方向"：**没有出现 manager → service 的逆向**，**没有 controller 没有 grpc 直接调 mapper**

---

## 一句话总结每个目录

| 目录 | 一句话 |
|------|--------|
| `grpc/` | 对外 9 个 RPC，纯转发 |
| `client/` | 调别人的，断路降级 |
| `service/` | 业务编排，事务边界 |
| `manager/` | 数据访问编排 + Redis 写合并 |
| `mapper/` | 一张表一个，纯 SQL |
| `entity/` | 表的 Java 映射 |
| `constant/` | Redis Key / 状态码 / 枚举 |
| `vo/` | 返回给上层的对象 |
| `job/` | 定时刷盘 / 重建 |
| `mq/` | 写扩散异步链路 |
| `config/` | 横切：缓存、雪花、ShedLock… |
| `exception/` | 业务异常 |
