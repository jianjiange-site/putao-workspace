# PostWriteService & FeedService 详解

> 结合代码实现与技术设计文档的深度学习文档

---

## 一、PostWriteService：发帖和删帖

### 1.1 它管什么

PostWriteService 负责两件事：**发帖**和**删帖**。代码行数不多（154 行），但每一步都值得掰开看。

### 1.2 发帖流程（createPost）

```java
public long createPost(Long userId, String content, List<String> imageKeys) {
    // 1. 生成雪花ID（不占事务）
    long postId = snowflakeIdGenerator.nextId();

    // 2. DB 写入（事务边界内）
    doCreatePost(postId, userId, content, imageKeys);

    // 3. 缓存帖子详情（事务外，失败不回滚）
    cachePostDetail(postId, userId, content, imageKeys);

    // 4. 加入冷启动池（事务外，失败不回滚）
    addToColdStartPool(postId, userId);

    // 5. 发 RocketMQ 做写扩散（事务外，失败不回滚）
    sendFanoutMessage(postId, userId);

    return postId;
}

/** DB 写入，事务边界. */
@Transactional(rollbackFor = Exception.class)
void doCreatePost(long postId, Long userId, String content, List<String> imageKeys) {
    postManager.createPost(postId, userId, content);
    postManager.saveImages(postId, imageKeys);
    postManager.initStats(postId);
}
```

**事务边界只覆盖 DB 写入**。Redis 缓存、冷启动池、MQ 发送都在事务外。为什么？

- DB 写在事务内，保证发帖的原子性（posts、post_images、post_stats 要么全成功，要么全回滚）
- Redis 写失败了 → 帖子已落库，缓存 miss 就 miss，下次查走 DB，不影响正确性
- 冷启动池写失败了 → 该帖少一个曝光渠道，但 5 分钟后热门池重建会兜底
- MQ 发失败了 → 同上，热门池兜底

这就是"**事务内保正确性，事务外独立失败独立恢复**"的思路。通过把 `@Transactional` 从 `createPost` 移到内部的 `doCreatePost`，实现了真正的事务边界划分——发帖接口本身必须在毫秒级返回，不能被 Redis 或 MQ 的抖动拖住。

### 1.3 发帖后的三件事



#### ① 缓存帖子详情

```java
private void cachePostDetail(Long postId, Long userId, String content, List<String> imageKeys) {
    try {
        String key = RedisKey.postDetail(postId);
        stringRedisTemplate.opsForHash().put(key, "userId", String.valueOf(userId));
        stringRedisTemplate.opsForHash().put(key, "content", content);
        stringRedisTemplate.opsForHash().put(key, "createdAt", String.valueOf(Instant.now().getEpochSecond()));
        if (imageKeys != null && !imageKeys.isEmpty()) {
            stringRedisTemplate.opsForHash().put(key, "imageKeys", String.join(",", imageKeys));
        }
        stringRedisTemplate.expire(key, Duration.ofDays(7));
    } catch (Exception e) {
        log.warn("Failed to cache post detail: postId={} error={}", postId, e.getMessage());
    }
}
```

用 Redis Hash 存帖子详情，TTL 7 天。这是 Cache Aside 模式：**写库后顺手写缓存**，读的时候先查 Redis，没命中再回源 DB。

#### ② 加入冷启动池

```java
private void addToColdStartPool(Long postId, Long userId) {
    try {
        boolean isMale = userClient.isMale(userId);
        String key = isMale ? RedisKey.coldStartPoolMale() : RedisKey.coldStartPoolFemale();
        long score = Instant.now().getEpochSecond();
        stringRedisTemplate.opsForZSet().add(key, String.valueOf(postId), score);
    } catch (Exception e) {
        log.warn("Failed to add to cold start pool: postId={} error={}", postId, e.getMessage());
    }
}
```

按发帖人性别写到不同的 ZSet，score 用时间戳。**男女各看异性的冷启动池**：男生看到的是女生发的新帖，女生看到的是男生发的新帖——这是约会 App 的业务逻辑。

注意这里有 try-catch。性别查询失败了不影响发帖，只是这帖暂时不进冷启动池。

#### ③ 写扩散：把帖子推入粉丝的 timeline

发帖只是把消息发到 MQ，实际的推送由 Consumer 异步完成。这整条链路叫**写扩散（Fanout）**。

先对比一下写扩散和读扩散的区别：


|              | 写扩散（Push）                  | 读扩散（Pull）            |
| ------------ | -------------------------- | -------------------- |
| **发帖时**      | 把帖子写入每个粉丝的 timeline（写 N 次） | 只写自己的帖子（写 1 次）       |
| **读 Feed 时** | 直接拉自己的 timeline（读 1 次）     | 查所有关注的人的帖子再合并（读 N 次） |
| **适合场景**     | 粉丝数少（约会 App 关注 < 500）      | 粉丝数无上限（微博明星过亿）       |


发帖只是把消息发到 MQ，实际的推送由 Consumer 异步完成。这整条链路叫**写扩散（Fanout）**。

**Producer 端**（发帖时同步执行，只发消息，不带关注者列表）：

```java
private void sendFanoutMessage(Long postId, Long userId) {
    try {
        long createdAtEpoch = Instant.now().getEpochSecond();
        postFanoutProducer.send(postId, userId, createdAtEpoch);
    } catch (Exception e) {
        log.warn("Failed to send fanout message: postId={} error={}", postId, e.getMessage());
    }
}
```

消息体只有三样东西：`postId`、`authorUserId`、`createdAtEpoch`。不预拉关注者列表——因为消息发出时和 Consumer 收到时关注关系可能变化，以 Consumer 执行时的最新关注列表为准才最新鲜。

**Consumer 端**（MQ 异步消费）：

```java
@RocketMQMessageListener(
        topic = "youjianxin-dating-dev-post-fanout-v1",
        consumerGroup = "youjianxin-dating-dev-post-service-fanout",
        maxReconsumeTimes = 16
)
public class PostFanoutConsumer implements RocketMQListener<FanoutMessage> {
    @Override
    public void onMessage(FanoutMessage message) {
        // 1. Consumer 收到消息，实时拉关注者列表
        List<Long> followers = userClient.getFriendUserIds(message.getAuthorUserId());
        if (followers.isEmpty()) return;

        // 2. 写入每个粉丝的 timeline
        for (long follower : followers) {
            writeToTimeline(follower, message);
        }
    }

    private void writeToTimeline(long followerId, FanoutMessage message) {
        String key = RedisKey.userTimeline(followerId);
        // ZADD，score = 发帖时间戳，member = postId
        stringRedisTemplate.opsForZSet().add(
                key,
                String.valueOf(message.getPostId()),
                message.getCreatedAtEpoch()
        );
        // 裁剪到 100 条
        Long size = stringRedisTemplate.opsForZSet().size(key);
        if (size != null && size > 100) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, size - 101);
        }
        // TTL 7 天
        stringRedisTemplate.expire(key, Duration.ofDays(7));
    }
}
```

ZSet 的 score 用发帖时间戳，所以 `ZREVRANGE` 就能按时间倒序取到好友的最新帖子。超过 100 条就把最老的踢掉（ZSet 自动按 score 排序）。

**为什么用 MQ 而不是发帖接口直接循环写？**

假设有 500 个粉丝，直接循环 ZADD 会让发帖接口从 ~~10ms 变成 ~500ms，且任何一个粉丝写失败都会污染整个事务。MQ 异步消费后，发帖接口只管发消息（~~10ms），Consumer 后台处理写扩散，失败了 RocketMQ 自动重投（最多 16 次），仍失败转 DLQ 告警。

**写扩散失败不影响发帖**。好友看不到这条帖只是"这条在 Feed 里晚几秒出现"，可接受的业务降级，热门池 5 分钟后重建可以兜底。

---



## 二、FeedService：三路混合推荐



### 2.1 它管什么

FeedService 负责推荐 Feed 的**读取**和**热门池重建**。这是整个 post-service 里逻辑最复杂的部分。

### 2.2 核心方法：getRecommendFeed

```java
public RecommendFeedVO getRecommendFeed(Long currentUserId, int pageSize, String cursor) {
    // 1. 解析游标 "recOffset:csOffset"
    int[] offsets = parseCursor(cursor);
    int recOffset = offsets[0];
    int csOffset = offsets[1];

    // 2. 获取用户性别
    boolean isMale = userClient.isMale(currentUserId);
    String oppositeGender = isMale ? "female" : "male";

    // 3. 三路并行取数据
    List<Long> recommendIds = getRecommendPool(oppositeGender, recOffset, pageSize + 10);
    List<Long> friendIds = getFriendTimeline(currentUserId, 5);
    List<Long> coldStartIds = getColdStartPool(oppositeGender, csOffset, 5);

    // 4. 获取布隆过滤器去重
    RBloomFilter<String> bloomFilter = getUserBloomFilter(currentUserId);

    // 5. 按槽位混排
    // ...
}
```



### 2.3 三路数据从哪来


| 池         | 获取方式        | 数据内容                               |
| --------- | ----------- | ---------------------------------- |
| **热门池**   | `ZREVRANGE` | Hacker News 公式打分的前 3000 条，5 分钟重建一次 |
| **好友时间线** | `ZREVRANGE` | 发帖时通过 MQ 写扩散到每个粉丝的 timeline        |
| **冷启动池**  | `ZREVRANGE` | 发帖时同步 ZADD，纯时间排序，给新帖曝光机会           |


**三路同时取，互不依赖**。任何一路空了，读侧都有降级策略：

- 好友池空了 → 降级到热门池
- 冷启动池空了 → 降级到热门池
- 热门池空了 → 返回空列表，App 显示"暂无内容"



### 2.4 布隆过滤器去重

```java
private RBloomFilter<String> getUserBloomFilter(Long userId) {
    String key = RedisKey.userReadBloom(userId);
    RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(key);
    bloomFilter.tryInit(5000, 0.01);
    return bloomFilter;
}
```

每个用户有一个布隆过滤器，记录"这个用户看过了哪些帖子"。容量 5000 个 ID，误判率 1%。三路取出来的 ID 全部过一遍布隆过滤器，已读的直接跳过。

**为什么用布隆过滤器而不是 Redis Set？**

- 5000 个 ID 用 Set 存需要 ~500KB
- 布隆过滤器只需要 ~6KB（5000 个 ID，1% 误判率）
- 误判的后果是"这条帖被跳过了"，用户最多少看 1% 的内容，体验无感



### 2.5 槽位混排逻辑

10 条为一页，位置分配规则：

```
位置  1, 2, 4, 5, 7, 8, 9, 10  → 推荐（热门池）主力位
位置  3                          → 好友强插
位置  6                          → 冷启动扶持
```

代码里的实现：

```java
List<Integer> recommendSlots = List.of(0, 1, 3, 4, 6, 7, 8, 9);
List<Integer> friendSlots = List.of(2);
List<Integer> coldStartSlots = List.of(5);
```

好友位和冷启动位都是**固定槽位**，而不是"有数据就插入"。好处是：用户每次刷新看到的位置是稳定的，不会因为好友发了帖整个 Feed 顺序乱跳。

**好友频控**：单页内同一个好友最多出现 1 次，防止话痨好友刷屏。

### 2.6 热门池重建（rebuildRecommendPool）

这是整个 Feed 体系里最有技术含量的部分，每 5 分钟执行一次。

#### 第一步：捞候选集

```java
List<PostEntity> recentPosts = postManager.listRecentPosts();
```

从 DB 捞近 3 天所有正常帖子，数量通常在几万条，一次性查完。

#### 第二步：批量获取计数（含 Redis 增量补偿）

```java
Map<Long, int[]> baseCounts = postStatManager.batchGetBaseCounts(postIds);

// Redis 实时增量补偿
int likeIncr = postStatManager.getRedisIncr(post.getPostId(), "likes");
int commentIncr = postStatManager.getRedisIncr(post.getPostId(), "comments");
int likes = baseLikes + likeIncr;
int comments = baseComments + commentIncr;
```

**为什么要补偿 Redis 增量？** 如果只用 DB 基准值打分，那两次重建之间的新点赞/评论全丢了，打出来的分是滞后的。补偿 Redis 增量后，分数是实时的。

#### 第三步：Hacker News 变体打分

```java
double hoursDiff = (now - post.getCreatedAt().getEpochSecond()) / 3600.0;
double score = (10.0 + 1.0 * likes + 3.0 * comments)
    / Math.pow(hoursDiff + 2, 1.5);
```

公式拆解：

- `10.0` 是基础分，防止新帖 0 赞时分数为 0
- `1.0 * likes + 3.0 * comments`：评论权重是点赞的 3 倍，因为评论成本更高，更能说明内容质量
- `/ (hoursDiff + 2)^1.5`：时间衰减因子。`+2` 是为了让新帖不会因为分母太小而分数爆炸；`1.5` 次方让衰减比线性更快——24 小时后分数约剩 1/25



#### 第四步：性别分桶

```java
Map<Long, Boolean> genderMap = userClient.getGenders(userIds);

for (PostEntity post : recentPosts) {
    Boolean isMale = genderMap.get(post.getUserId());
    if (isMale != null && isMale) {
        maleScores.add(Map.entry(post.getPostId(), score));
    } else {
        femaleScores.add(Map.entry(post.getPostId(), score));
    }
}
```

- 男生发的帖 → 归入 maleScores，最终写进 `feed:pool:recommend:male`
- 女生发的帖 → 归入 femaleScores，最终写进 `feed:pool:recommend:female`
- 读的时候：男用户看 female 池（异性优先），女用户看 male 池

**为什么要批量 RPC？** 3 万条帖子可能涉及 2 万个不同用户，如果逐条调 `getGender(userId)`，就是 2 万次 gRPC 调用。改成批量 `getGenders(List<Long>)` 只需要几次 RPC。

#### 第五步：影子池原子替换

```java
// 写临时池
writePoolToTemp(RedisKey.feedPoolRecommendMaleTmp(), topMale);
writePoolToTemp(RedisKey.feedPoolRecommendFemaleTmp(), topFemale);

// 原子改名
stringRedisTemplate.rename(RedisKey.feedPoolRecommendMaleTmp(), RedisKey.feedPoolRecommendMale());
stringRedisTemplate.rename(RedisKey.feedPoolRecommendFemaleTmp(), RedisKey.feedPoolRecommendFemale());
```

**为什么要先写临时池再改名，而不是直接写正式池？**

如果直接写正式池（比如先 DEL 再 ZADD），在 DEL 之后、新 ZADD 之前的窗口期内，用户会看到一个空的热门池。改成写临时池 + 原子改名，用户要么看到旧池，要么看到新池，**永远看不到空池**。

Redis 的 `RENAME` 是原子操作，不会出现中间状态。

---



## 三、核心设计思想



### 3.1 写合并（Write Coalescing）

点赞和评论的计数为什么不直接写 DB，而要用 Redis 中转？

**直接 UPDATE 的问题**：1000 个人同时点赞同一个帖子，PG 会把这 1000 个 UPDATE 串行化。第 1000 个用户要等 3 秒才能拿到响应，而且整个 PG 连接池都被这一行锁住。

**解决方案**：

```
写：点赞 → Redis INCR + SADD updated_set（~1ms，不碰 DB）
读：点赞数 = DB 基准值 + Redis 增量（对用户永远是实时的）
刷盘：每分钟 Job → Lua 原子取增量 → 批量 UPDATE 到 DB
```

**Lua 脚本为什么必须原子？** 如果拆成 `GET` + `SET 0` 两条命令，在两次调用之间可能又有新点赞，`SET 0` 会把那个赞覆盖掉。Redis 单线程执行 Lua 可以保证中间没有其他命令插入。

### 3.2 读扩散 vs 写扩散

好友时间线用写扩散（发帖时推送到粉丝的 timeline），而不是读扩散（读的时候查所有好友的最新帖子）。


|           | 写扩散                  | 读扩散            |
| --------- | -------------------- | -------------- |
| 发帖成本      | O(N)，N = 粉丝数         | O(1)           |
| 读 Feed 成本 | O(1)，直接拉自己的 timeline | O(N)，查所有好友的帖子  |
| 适合场景      | 粉丝数有上限（约会 App < 500） | 粉丝数无上限（微博明星过亿） |




### 3.3 缓存的定位

Redis 在这个项目里有两种完全不同的用法：


| 用法                  | 场景       | 例子                          |
| ------------------- | -------- | --------------------------- |
| **Delta 累加器**（不是缓存） | 写合并的增量中转 | `stat:incr:{post_id}:likes` |
| **滑动窗口缓存**          | 热点数据加速读  | 评论 ZSet（最新 200 条）           |
| **Cache Aside**     | 通用缓存     | 帖子详情 Hash                   |


帖子详情缓存用 Cache Aside（写库后写缓存），而计数增量用 Delta 累加器（永远用 DB 做真值，Redis 只存未刷盘的 delta）——这是两种不同的缓存策略，不能混用。

### 3.4 MQ 在事务外的设计

发帖流程里，发 MQ 放在 `@Transactional` 之外。为什么？

- 如果 MQ 发送失败就回滚 DB，发帖接口会因为 Broker 抖动而失败，用户体验差
- MQ 失败不回滚，帖子已落库，用户看到发帖成功
- MQ 失败的后续：Consumer 收不到消息 → 该帖好友时间线里没有这条 → 5 分钟后热门池重建可以兜底

这是一个**最终一致性 vs 强一致性**的权衡。约会 App 里好友帖晚几秒出现在 Feed 里完全可接受，不需要为此上分布式事务或 Outbox 表。

---



## 四、面试关联



### 4.1 FeedService 值得展开的点


| 点                  | 为什么值得讲             | 怎么讲                                                   |
| ------------------ | ------------------ | ----------------------------------------------------- |
| **三路混合推荐**         | 体现了"多信号融合"的系统设计思维  | 讲清楚三路各自解决什么问题、槽位为什么这么分配、降级策略                          |
| **Hacker News 公式** | 经典算法的工程落地          | 讲清楚 `(基础分 + 权重*点赞 + 权重*评论) / (时间衰减)` 的设计意图，权重为什么是 1:3 |
| **影子池原子替换**        | Redis 原子性 + 零窗口期设计 | 讲清楚"先写临时池再改名"的思路，对比直接写的窗口期问题                          |
| **性别分桶**           | 业务逻辑融入技术设计         | 讲清楚约会 App"异性优先"的业务背景                                  |
| **布隆过滤器**          | 省内存的去重方案           | 讲清楚假阳性 vs 假阴性、适用场景                                    |




### 4.2 PostWriteService 值得展开的点


| 点                  | 为什么值得讲              | 怎么讲                                  |
| ------------------ | ------------------- | ------------------------------------ |
| **事务边界设计**         | 什么放事务内、什么放事务外是常见面试题 | 讲清楚"事务内保正确性，事务外独立失败独立恢复"             |
| **MQ 事务一致性**       | 最终一致性的取舍            | 讲清楚为什么接受"事务提交但 MQ 失败"的窗口期，以及热门池的兜底作用 |
| **Cache Aside 缓存** | 最常用的缓存模式            | 讲清楚写库后写缓存的顺序，以及为什么删缓存而不是更新缓存         |




### 4.3 和设计文档的对应关系


| 代码实现                   | 设计文档章节                       | 关键设计                      |
| ---------------------- | ---------------------------- | ------------------------- |
| `createPost` 事务边界      | §9.1 步骤 3                    | `@Transactional` 只覆盖 DB 写 |
| `cachePostDetail`      | §6.1 `post:detail:{post_id}` | Cache Aside               |
| `addToColdStartPool`   | §10.2.3 冷启动池                 | 性别分桶、同步写入                 |
| `sendFanoutMessage`    | §10.2.2 好友时间线                | RocketMQ 写扩散              |
| `getRecommendFeed`     | §10.3 三路混合                   | 槽位分配 + 降级策略               |
| `rebuildRecommendPool` | §10.2.1 全网热门池                | Hacker News + 影子池         |
| 计数增量补偿                 | §6.2 写合并                     | Redis 累加 + 批量刷盘           |


