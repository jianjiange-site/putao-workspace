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

## 关联知识速查

| 技术点 | 在本模块的应用 |
|--------|---------------|
| **幂等性** | post_likes 联合主键 + ON CONFLICT |
| **缓存一致性** | 写合并模式 |
| **分布式锁** | Redisson 分布式锁 |
| **消息队列** | RocketMQ 异步写扩散 |
| **布隆过滤器** | 已读去重 |
| **数据库设计** | 按 user_id 分表预留 |
