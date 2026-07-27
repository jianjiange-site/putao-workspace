# Post-Service 知识点整理

> 纯技术知识学习，不涉及面试场景

---

## 知识点一：高并发计数 - Redis 写合并模式

### 1. 背景/场景

约会 App 的帖子点赞功能，爆款帖可能 1 秒内 1000 人同时点赞。如果每次点赞都直接 UPDATE 数据库，会导致单行锁串行，拖垮整个服务。

### 2. 问题分析

**直接 UPDATE 的问题**：

```sql
UPDATE post_stats SET like_count = like_count + 1 WHERE post_id = 999;
```

PostgreSQL 的 UPDATE 在事务提交前持有**行级排他锁**：

```
请求1   [获锁|读|写|fsync|放锁]  done @ 3ms
请求2          等待...            done @ 6ms
请求3                    等待...  done @ 9ms
...
请求1000                              ... done @ 3000ms
```

**灾难后果**：
- 第 1000 个用户等 3 秒
- PG 连接池被同一行锁住，整个服务全部连不上 DB
- 上游超时 → 重试 → 雪崩

### 3. 解决方案

**核心思想**：把"高频写"和"持久化"在时间和介质上解耦

**写路径**（用户点赞）：
```
1. post_likes 表 upsert（用户级幂等记录）
2. Redis INCR 累加计数
3. SADD 待刷盘 Set
4. 返回 ~1ms
```

**读路径**（查看点赞数）：
```
实时 likes = DB基准值 + Redis增量
```

**刷盘路径**（每分钟定时任务）：
```
1. 随机取 100 个待刷盘 post_id
2. Lua 原子 GET + SET 0
3. 批量 UPDATE DB
```

### 4. 实现细节

**点赞时 Redis 累加**：

```java
public void actionLike(Long userId, Long postId, boolean like) {
    // 1. 幂等记录（联合主键 + ON CONFLICT upsert）
    boolean changed = postLikeManager.upsert(userId, postId, like ? 1 : 0);
    if (!changed) return; // 已是目标状态，幂等返回

    // 2. Redis 增量
    String incrKey = "putao:post:stat:incr:" + postId + ":likes";
    redisTemplate.opsForValue().increment(incrKey, like ? 1 : -1);

    // 3. 标记待刷盘
    redisTemplate.opsForSet().add("putao:post:updated_set", postId);
}
```

**刷盘时 Lua 原子操作**：

```java
public void flushLikes() {
    Set<Object> postIds = redisTemplate.opsForSet()
        .distinctRandomMembers("putao:post:updated_set", 100);

    for (Object postId : postIds) {
        String incrKey = "putao:post:stat:incr:" + postId + ":likes";

        // Lua 脚本：GET + SET 0 原子执行，防止新点赞丢失
        String luaScript =
            "local v = redis.call('GET', KEYS[1]); " +
            "redis.call('SET', KEYS[1], 0); " +
            "return v;";

        Long delta = redisTemplate.execute(
            new DefaultRedisScript<>(luaScript, Long.class),
            Collections.singletonList(incrKey)
        );

        if (delta != null && delta != 0) {
            postStatManager.incrementLikeCount((Long) postId, delta);
        }
    }
}
```

**读时 DB + Redis 合并**：

```java
public PostDetailVO getPostDetail(Long postId) {
    // 从 DB 读基准值
    PostStatEntity stat = postStatManager.getById(postId);
    long baseLikes = stat.getLikeCount();

    // 从 Redis 读增量
    Long incr = redisTemplate.opsForValue()
        .get("putao:post:stat:incr:" + postId + ":likes");

    return new PostDetailVO(stat, baseLikes + (incr != null ? incr : 0));
}
```

### 5. 权衡取舍

| 方案 | 优点 | 缺点 | 选择 |
|------|------|------|------|
| 直接 UPDATE | 简单 | 单行锁串行，高并发必崩 | ❌ |
| Redis 写合并 | 高性能，PG 压力小 | 有 1 分钟数据滞后 | ✅ |
| 消息队列 | 异步解耦 | 增加复杂度 | 太重 |

**为什么 Lua 脚本要原子执行？**

```java
// 错误写法（会丢数据）
v = redis.GET(key);      // 假设拿到 1000
redis.SET(key, 0);       // 这期间又有赞，变成 1001
// 1001 被覆盖，丢失

// 正确写法（Lua 原子）
EVAL "local v = redis.call('GET', KEYS[1]); redis.call('SET', KEYS[1], 0); return v;"
// Redis 单线程执行，Lua 脚本期间不接受其他命令
```

### 6. 关联知识

- Redis 数据持久化：RDB/AOF
- Redis 单线程模型：为什么快
- 分布式定时任务：ShedLock

---

## 知识点二：Feed 流推荐 - 三路池混合

### 1. 背景/场景

约会 App 的 Feed 流需要兼顾：
- **优质内容曝光**：让热门帖子排在前面
- **社交属性**：好友发的动态要看到
- **新帖机会**：解决马太效应，新帖 0 赞永远排不上去

### 2. 问题分析

单一池无法同时满足三个需求：
- 纯时间序：新帖友好，但热门内容沉底
- 纯热度序：热门友好，但新帖没机会
- 好友序：社交友好，但没好友的人看不到内容

### 3. 解决方案

**三路池设计**：

```
┌─────────────────────────────────────────────────────────────┐
│  ① 全网热门池 (pull)     │  每5分钟Job重建，按热度分排序   │
│     容量：每性别 Top 3000                                        │
│                                                             │
│  ② 好友时间线 (push)     │  发帖时写扩散，单向好友可见     │
│     容量：每人最近 100 条                                         │
│                                                             │
│  ③ 冷启动池              │  新帖立刻进入，按时间排序       │
│     容量：每性别 10000 条                                         │
└─────────────────────────────────────────────────────────────┘
```

**位置分配策略**（10条为一页）：

| 位置 | 来源 | 原因 |
|------|------|------|
| 1,2,4,5,7,8,9,10 | 热门池 | 主力推荐位 |
| 3 | 好友时间线 | 强插，保证社交属性 |
| 6 | 冷启动池 | 扶持新帖 |

### 4. 实现细节

**三路并行拉取**：

```java
public FeedResult getRecommendFeed(Long userId, int pageSize) {
    // 1. 获取用户性别（异性优先）
    Gender myGender = userClient.getGender(userId);
    Gender targetGender = (myGender == Gender.MALE) ? Gender.FEMALE : Gender.MALE;

    // 2. 并行拉三路数据
    CompletableFuture<List<Long>> recFuture = CompletableFuture.supplyAsync(() ->
        zsetOps.reverseRangeWithScores(
            "putao:feed:pool:recommend:" + targetGender, 0, pageSize * 2)
    );
    CompletableFuture<List<Long>> friendFuture = CompletableFuture.supplyAsync(() ->
        zsetOps.reverseRangeWithScores(
            "putao:user:timeline:" + userId, 0, 5)
    );
    CompletableFuture<List<Long>> coldStartFuture = CompletableFuture.supplyAsync(() ->
        zsetOps.reverseRangeWithScores(
            "putao:feed:cold_start:pool:" + targetGender, 0, 3)
    );
    // ...
}
```

**布隆过滤器去重**：

```java
BloomFilter<Long> bloom = redissonClient.getBloomFilter(
    "putao:user:read:bloom:" + userId  // 容量5000，误判率1%
);

// 去重判断
if (bloom.contains(postId)) {
    // 已读，跳过
    continue;
}
bloom.add(postId);
```

**混排算法**：

```java
private List<Long> mergeThreeWay(
        List<Long> rec, List<Long> friend, List<Long> coldStart,
        BloomFilter<Long> bloom, int pageSize) {

    List<Long> result = new ArrayList<>();
    Set<Long> usedFriends = new HashSet<>();  // 同好友频控
    int recIdx = 0, friendIdx = 0, csIdx = 0;

    for (int pos = 1; pos <= pageSize && result.size() < pageSize; pos++) {
        Long postId = null;

        if (pos == 3 && friendIdx < friend.size()) {
            postId = friend.get(friendIdx++);
        } else if (pos == 6 && csIdx < coldStart.size()) {
            postId = coldStart.get(csIdx++);
        } else if (recIdx < rec.size()) {
            postId = rec.get(recIdx++);
        }

        if (postId != null && !bloom.contains(postId)) {
            result.add(postId);
        }
    }
    return result;
}
```

### 5. 权衡取舍

**热门池为什么 5 分钟重建一次？**

Hacker News 热度公式：
```
Score = (基础分 + α×点赞 + β×评论) / (发布时间+2)^1.5
```

分数随时间被动下降，不需要实时维护。5 分钟延迟用户感知不到排序变化，但能省掉天量计算。

**布隆过滤器参数选择**：

| 参数 | 值 | 理由 |
|------|-----|------|
| 容量 | 5000 | 预计 7 天内阅读量 |
| 误判率 | 1% | 平衡内存和准确性 |
| TTL | 7 天 | 过期后可重新看到老帖 |

### 6. 关联知识

- 推荐系统基础：协同过滤、内容推荐
- 布隆过滤器原理
- Redis ZSet 应用

---

## 知识点三：写扩散 - RocketMQ 异步模式

### 1. 背景/场景

用户发了一条动态，需要推送给所有粉丝（写扩散）。粉丝数可能很多（数百到数千），不能同步写入，否则会阻塞用户请求。

### 2. 问题分析

**方案对比**：

| 方案 | 写 | 读 | 适用场景 |
|------|-----|-----|----------|
| 写扩散 | O(N) | O(1) | N 有限（<1000） |
| 读扩散 | O(1) | O(N) | 粉丝无上限 |
| 混合 | - | - | 动态选择 |

约会 App 场景：用户关注数有限（通常 <500），但读 Feed 要极快 → 选写扩散

### 3. 解决方案

**整体架构**：

```
发帖事务 COMMIT
      │
      ▼
┌─────────────────┐
│  RocketMQ       │  异步发送
│  syncSend       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Consumer       │  拉取粉丝列表
│  并发消费       │  ZADD timeline
└─────────────────┘
```

### 4. 实现细节

**Producer 发消息**：

```java
@Component
public class PostFanoutProducer {
    private static final String TOPIC = "putao-dating-dev-post-fanout-v1";

    public void send(long postId, long authorUserId, long createdAtEpoch) {
        FanoutMessage msg = new FanoutMessage(postId, authorUserId, createdAtEpoch);

        for (int i = 0; i < 3; i++) {
            try {
                var result = template.syncSend(TOPIC, msg, 2000);
                if (SendStatus.SEND_OK == result.getSendStatus()) return;
            } catch (Exception e) {
                log.warn("fanout send retry, postId={} attempt={}", postId, i + 1);
            }
        }
        // 3次全失败，记录指标，不阻塞返回
        metrics.counter("post.fanout.produce.fail").increment();
    }
}
```

**Consumer 消费消息**：

```java
@RocketMQMessageListener(
    topic = "putao-dating-dev-post-fanout-v1",
    consumerGroup = "putao-dating-dev-post-service-fanout",
    consumeMode = ConsumeMode.CONCURRENTLY  // 并发消费
)
public class PostFanoutConsumer implements RocketMQListener<FanoutMessage> {

    @Override
    public void onMessage(FanoutMessage msg) {
        // 1. 拉取粉丝列表（Caffeine 30s 缓存）
        List<Long> followers = userClient.getFriendUserIds(msg.authorUserId());
        if (followers.isEmpty()) return;

        // 2. 写入每个粉丝的 timeline
        for (Long follower : followers) {
            String key = "putao:user:timeline:" + follower;
            // ZADD + 裁剪 + TTL，一条命令
            redisTemplate.opsForZSet().add(key, msg.postId(), msg.createdAtEpoch());
            redisTemplate.opsForZSet().removeRange(key, 0, -101);  // 只留100条
            redisTemplate.expire(key, 7, TimeUnit.DAYS);
        }
    }
}
```

### 5. 权衡取舍

**为什么用 MQ 而不是 @Async？**

| 维度 | @Async | RocketMQ |
|------|--------|----------|
| 进程崩溃 | 任务丢失 | Broker 持久化 |
| 重试 | 手动实现 | 自动 16 次 |
| 扩展 | 单进程 | 水平扩展 |

**事务-消息一致性**：

不上 Outbox，接受"事务提交但 MQ 发送失败"的极小窗口：
- Producer 本地 retry 3 次可覆盖正常 Broker 抖动
- 5 分钟后热门池重建可兜底
- 增加 Outbox 复杂度不划算

### 6. 关联知识

- RocketMQ 事务消息
- 消息队列顺序性
- 消费幂等设计

---

## 知识点四：点赞幂等设计

### 1. 背景/场景

用户重复点击点赞按钮，或者多端同时点赞，需要保证最终状态正确。

### 2. 问题分析

- 并发插入：同一个人对同一帖子点赞两次
- 重复提交：网络重试导致重复请求
- 多端操作：手机、平板同时点赞

### 3. 解决方案

**数据库层幂等**：联合主键 + ON CONFLICT

```sql
INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at)
VALUES (#{userId}, #{postId}, #{status}, NOW(), NOW())
ON CONFLICT (user_id, post_id)
DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()
WHERE post_likes.status <> EXCLUDED.status;
```

**关键设计**：
- 联合主键 `(user_id, post_id)` 防重复
- `ON CONFLICT DO UPDATE` 实现 upsert
- `WHERE` 条件过滤无效更新

### 4. 实现细节

```java
public boolean upsert(Long userId, Long postId, Integer status) {
    int affected = postLikeMapper.upsert(userId, postId, status);
    // affected = 0：已是目标状态（幂等）
    // affected = 1：状态真变了
    return affected == 1;
}
```

```xml
<insert id="upsert">
    INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at)
    VALUES (#{userId}, #{postId}, #{status}, NOW(), NOW())
    ON CONFLICT (user_id, post_id)
    DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()
    WHERE post_likes.status <> EXCLUDED.status
</insert>
```

### 5. 权衡取舍

**为什么不 DELETE 而是用 status 标记？**

| 方案 | 优点 | 缺点 |
|------|------|------|
| DELETE | 数据干净 | 再次点赞需 INSERT，可能冲突 |
| UPDATE status | 复用同一行，无冲突 | 数据冗余 |

选择 UPDATE status：复用行避免插入冲突，未来如需查询"历史点赞记录"也有数据。

---

## 知识点五：点赞技术选型对比

### 1. 背景

在实际生产中，点赞功能有多种实现路径，技术选型的核心在于**数据一致性**与**系统性能**之间的权衡。

### 2. 常见方案对比

| 方案 | 一致性 | 响应性能 | 数据安全 | 实现复杂度 |
|------|--------|----------|----------|------------|
| 直接写 DB | **强一致** | 低（DB 行锁串行） | 零丢失 | 最低 |
| DB + Redis 计数（无刷盘） | 最终一致 | 高 | Redis 丢则计数丢 | 低 |
| DB + Redis 缓冲 + 定时刷盘（当前） | 最终一致 | 高 | Redis 丢则增量丢 | 中 |
| 纯 Redis + MQ 异步落库 | 最终一致 | 极高 | Redis 丢则全丢 | 高 |
| 分段计数（分桶） | 最终一致 | 极高 | 同上 | 高 |

### 3. 各方案详解

**方案 A：直接写 DB**

```sql
UPDATE post_stats SET like_count = like_count + 1 WHERE post_id = ?;
```

- 优点：实现简单，强一致，无数据丢失风险
- 缺点：单行锁串行，1000 QPS 时第 1000 个请求等待 3 秒；PG 连接池被打满影响全服务
- 适用场景：点赞量极小（< 50 QPS）的冷启动阶段

**方案 B：DB + Redis 计数（无刷盘）**

```
点赞 → INSERT post_like + Redis INCR → 读数直接返回 Redis
```

- 优点：读性能极好，点赞关系落 DB 保证可靠
- 缺点：Redis 重启或故障时计数归零，需要从 DB 重建
- 适用场景：对计数准确性要求一般的产品

**方案 C：DB + Redis 缓冲 + 定时刷盘（当前方案）**

```
点赞 → INSERT post_like → Redis INCR → SADD 待刷盘队列
刷盘（每分钟）→ Lua GET+SET 0 → UPDATE DB
读数 → DB基准值 + Redis增量
```

- 优点：写路径极快（~1ms），批量刷盘减少 DB IO，定时任务兜底不丢数据
- 缺点：存在最多 60 秒的计数延迟；Redis 故障时未刷盘增量丢失
- 适用场景：点赞计数允许秒级延迟的业务（如 dating 场景）

**方案 D：纯 Redis + MQ 异步落库**

```
点赞 → Redis ZADD/SET 存储关系 + INCR 计数 → 发 MQ 消息
消费端 → 批量攒消息 → 批量写 DB
```

- 优点：点赞响应极快（< 1ms），MQ 削峰能力强
- 缺点：Redis 故障则点赞功能完全不可用；需要额外处理 MQ 消费幂等、重试；每条消息触发一次 DB 写（需攒批才能省 IO）
- 适用场景：点赞延迟必须毫秒级的场景（直播间热榜、实时排行）



### 4. 为什么当前项目选方案 C（定时刷盘）

**选型依据**：dating 场景下点赞数据的特征：

| 维度 | 分析 |
|------|------|
| **价值属性** | 点赞是"低价值、高频、可延迟"数据，用户最在意操作成功，不在意计数晚几秒更新 |
| **准确性要求** | `post_stat.like_count` 只是展示用，不是扣费/库存等敏感数据，允许秒级误差 |
| **批量效率** | 定时刷盘天然攒批，100 个 postId 一次 DB 写；MQ 每条消息一次 DB（除非额外攒批） |
| **运维复杂度** | 只用 `@Scheduled` + ShedLock，不引入额外组件 |
| **可靠性** | 定时任务兜底，MQ 挂了/消息丢了也能最终一致 |



### 6. 关联知识

- Redis 数据持久化：RDB/AOF
- Redis 单线程模型：为什么快
- 分布式定时任务：ShedLock
- 消息队列选型：RocketMQ vs Kafka

---

## 知识点六：楼中楼评论 — 评论嵌套与滑动窗口

### 1. 背景/场景

约会 App 的帖子评论需要支持楼中楼（嵌套回复）。用户可以对帖子直接评论，也可以回复任意一条已有评论，形成多层级结构。

### 2. 问题分析

楼中楼的核心挑战有两个：

**结构设计**：如何用一张表表达任意层级的评论关系？

**性能问题**：热帖可能成千上万条评论，每次翻页都直接查 DB 会把数据库打爆。

### 3. 解决方案

**表结构设计**：三个字段搞定嵌套关系

```java
private Long rootId;      // 根评论ID（自身是根则为0）
private Long parentId;    // 直接父评论ID
private Long replyToUserId; // 被回复人的 user_id
```

这三个字段组合出所有场景：

```
场景1：评论帖子（一级评论）
  rootId=0, parentId=0, replyToUserId=0

场景2：回复一级评论（楼中楼）
  rootId=一级评论ID, parentId=一级评论ID, replyToUserId=一级评论作者ID

场景3：回复楼中楼内某条（嵌套楼中楼）
  rootId=一级评论ID, parentId=目标楼中楼ID, replyToUserId=目标作者ID
```

**性能方案**：Redis ZSet 滑动窗口

每个帖子维护一个 ZSet，key = `putao:post:post:comments:<postId>`：

```
members: commentId（评论业务ID）
scores:  commentId（用作排序分，天然倒序）
```

因为 commentId 是 Snowflake 时间戳，越新的越大，所以 `ZREVRANGE` 天然就是按时间倒序。

### 4. 实现细节

**写评论（先写 DB，再更新 ZSet）**：

```java
public long createComment(Long postId, Long userId, String content,
                          Long rootId, Long parentId) {
    long commentId = snowflakeIdGenerator.nextId();

    // 1. 写入 DB
    PostCommentEntity comment = new PostCommentEntity();
    comment.setCommentId(commentId);
    comment.setPostId(postId);
    comment.setUserId(userId);
    comment.setContent(content);
    comment.setRootId(rootId != null ? rootId : 0L);
    comment.setParentId(parentId != null ? parentId : 0L);
    comment.setReplyToUserId(0L);  // TODO: 接入真实 replyToUserId
    comment.setStatus(1);
    commentMapper.insert(comment);

    // 2. 加入 Redis ZSet（score = commentId，天然倒序）
    String key = RedisKey.commentsZSet(postId);
    stringRedisTemplate.opsForZSet().add(key, String.valueOf(commentId), commentId);

    // 3. 裁剪到最新 200 条
    Long size = stringRedisTemplate.opsForZSet().size(key);
    if (size != null && size > MAX_COMMENT_WINDOW) {
        stringRedisTemplate.opsForZSet()
            .removeRange(key, 0, size - MAX_COMMENT_WINDOW - 1);
    }
    stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);

    // 4. Redis INCR 评论计数 + 标记待刷盘
    postStatManager.incrRedisComment(postId, 1);
    stringRedisTemplate.opsForSet().add(RedisKey.updatedSet(), String.valueOf(postId));

    return commentId;
}
```

**读评论（ZSet → 回库批量查）**：

```java
public List<PostCommentEntity> listComments(Long postId, Long cursor, int pageSize) {
    String key = RedisKey.commentsZSet(postId);

    // 首次翻页：从 ZSet 取最新 pageSize 条
    if (cursor == null || cursor <= 0) {
        Set<String> commentIds = stringRedisTemplate.opsForZSet()
            .reverseRange(key, 0, pageSize - 1);
        if (commentIds != null && !commentIds.isEmpty()) {
            return getCommentsByIds(parseIds(commentIds)); // 批量回库
        }
    } else {
        // 游标翻页：取比 cursor 小的（即更新的）
        Set<String> commentIds = stringRedisTemplate.opsForZSet()
            .reverseRangeByScore(key, 0, cursor - 1, 0L, pageSize);
        if (commentIds != null && !commentIds.isEmpty()) {
            return getCommentsByIds(parseIds(commentIds));
        }
    }

    // 冷帖或超过200条：直接回源 DB
    return listCommentsFromDb(postId, cursor, pageSize);
}
```

**批量回库查详情**：

```java
private List<PostCommentEntity> getCommentsByIds(List<Long> commentIds) {
    List<PostCommentEntity> comments = commentMapper.selectList(
        new LambdaQueryWrapper<PostCommentEntity>()
            .in(PostCommentEntity::getCommentId, commentIds)
    );
    // 按 commentId 倒序（与 ZSet 顺序一致）
    comments.sort((a, b) -> Long.compare(b.getCommentId(), a.getCommentId()));
    return comments;
}
```

**异步刷盘（每分钟）**：

```java
@Scheduled(fixedRate = 60_000)
@SchedulerLock(name = "post.commentFlush", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
public void flushComments() {
    Set<String> postIds = stringRedisTemplate.opsForSet()
        .distinctRandomMembers(RedisKey.updatedSet(), BATCH_SIZE);

    for (String postIdStr : postIds) {
        Long postId = Long.parseLong(postIdStr);

        // Lua 原子 GET + SET 0
        int delta = atomicGetAndReset(postId);
        if (delta != 0) {
            postStatManager.incrementCommentCount(postId, delta);
        }
        stringRedisTemplate.opsForSet().remove(RedisKey.updatedSet(), postIdStr);
    }
}
```

### 5. 案例

用户 A 发了一篇帖子，用户 B、C、D 陆续评论：

```
帖子 #101 (postId=101)

用户 B 评论："这个活动好棒！"
  commentId=8001, rootId=0, parentId=0

    用户 C 回复 B："确实，我也想去！"（楼中楼）
      commentId=8002, rootId=8001, parentId=8001, replyToUserId=B的用户ID

        用户 D 回复 C："加我一个！"（嵌套楼中楼）
          commentId=8003, rootId=8001, parentId=8002, replyToUserId=C的用户ID

    用户 E 回复 B："哪里报名？"（楼中楼）
      commentId=8004, rootId=8001, parentId=8001, replyToUserId=B的用户ID

用户 F 评论："求组队"
  commentId=8005, rootId=0, parentId=0
```

**查一级评论列表**（`rootId=0` 的评论，按时间倒序）：

- 返回：commentId=8005（F）、commentId=8001（B）

**查 B 的楼中楼**（`rootId=8001` 的评论）：

- 返回：commentId=8004（E）、commentId=8003（D）、commentId=8002（C）

**前端展示逻辑**：
- 一级评论列表展示 rootId=0 的评论
- 每个一级评论下面，点击"查看更多回复"时，用 `rootId` 拉取该根下的所有楼中楼
- 每条评论显示 `replyToUserId` 对应用户的头像/昵称

### 6. 权衡取舍

|| 设计点 | 选择 | 理由 |
|------|--------|------|------|
| 嵌套层级 | 无限制（通过 rootId 追溯） | 理论上支持无限层，但 UI 通常限制 3 层以内 |
| ZSet 窗口 | 200 条 | 平衡内存与体验，超过则回源 DB |
| ZSet TTL | 7 天 | 冷帖超过7天访问概率极低，自动过期释放内存 |
| 计数一致性 | 最终一致（1分钟延迟） | 与点赞计数共用一套刷盘机制，一致处理 |

**为什么不用 `parentId` 查楼中楼，而用 `rootId`？**

因为 `parentId` 只能查到直接子评论，`rootId` 才能查到该评论线程下的所有回复。

---

## 关联知识速查

| 技术点 | 在本模块的应用 |
|--------|---------------|
| **幂等性** | post_likes 联合主键 + ON CONFLICT |
| **缓存一致性** | 写合并模式 |
| **分布式锁** | Redisson 分布式锁 |
| **消息队列** | RocketMQ 异步写扩散 |
| **布隆过滤器** | 已读去重 |
| **数据库设计** | 按 user_id 分表预留 |
