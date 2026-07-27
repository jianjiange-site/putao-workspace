# match-service 代码证据速查(Code Evidence)

> 配套：[`knowledge.md`](./knowledge.md)（知识点）/ [`interview-qa.md`](./interview-qa.md)（面试问答）
>
> 本文件目的：**每个知识点附上真实代码片段 + 文件:行号**,面试时被追问可以秒翻源码佐证。
>
> 所有引用均来自 `dating-server/match-service/src/main/java/com/dating/match/`。
>
> **核对日期**：2026-07-23

---

## 知识点二：Redis LIST + LPOP 即消费的队列模型

**核心代码**：`service/FeedService.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/service/FeedService.java
// 80-94: while 循环 LPOP + 二次过滤
while (result.size() < need && safetyRounds < 8) {
    safetyRounds++;
    String feedKey = MatchRedisKey.feed(userId);
    int batchSize = need - result.size();
    List<String> batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
    if (batch == null || batch.isEmpty()) {
        // 队列空 → 触发冷启动重建
        log.debug("Feed empty, rebuilding via cold start userId={}", userId);
        coldStartService.buildAndPush(userId);
        batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
        if (batch == null || batch.isEmpty()) {
            break; // 极端兜底
        }
    }

// 107-113: 二次过滤(逐个 isMember,实际生产可用 SMISMEMBER 优化)
String swipedKey = MatchRedisKey.swiped(userId);
List<Object> swipedArr = java.util.Arrays.asList(targetIds.toArray());
java.util.List<Boolean> swipedHits = new java.util.ArrayList<>(swipedArr.size());
for (Object tid : swipedArr) {
    Boolean hit = stringRedisTemplate.opsForSet().isMember(swipedKey, tid);
    swipedHits.add(Boolean.TRUE.equals(hit));
}

// 158-161: swipe 接口同步 SADD 到 swiped SET(消费阶段二次过滤的 cache)
public void markSwiped(long userId, long targetUserId) {
    String swipedKey = MatchRedisKey.swiped(userId);
    stringRedisTemplate.opsForSet().add(swipedKey, String.valueOf(targetUserId));
}
```

**Swipe 链路同步 SADD 调用点**：

```java
// dating-server/match-service/src/main/java/com/dating/match/service/SwipeService.java:122-123
// 5. 写 user_swipe_history(同事务)
SwipeRespVO resp = writeHistoryAndTrigger(userId, targetUserId, targetType, direction);
// 6. SADD 到 match:swiped(消费阶段二次过滤)
feedService.markSwiped(userId, targetUserId);
```

**关键观察**：
- `leftPop(key, batchSize)` —— Redis 7.0 才支持 `count` 参数,版本要求落地
- `safetyRounds < 8` —— 死循环保险,极端 cold start 还空时退出
- 二次过滤是**逐个 isMember**（不是 SMISMEMBER 批量）—— 真实生产可优化为 SMISMEMBER 单次 RPC 节省 RTT

---

## 知识点三：DH 延迟匹配(15s ~ 2min 进程内调度)

**核心代码**：`service/DhDelayedMatchService.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/service/DhDelayedMatchService.java
// 25-29: 常量定义
private static final long MIN_DELAY_MS = 15_000L;
private static final long MAX_DELAY_MS = 120_000L;

// 49-69: 调度核心
public void scheduleDelayedMatch(long userId, long dhId) {
    long delayMs = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);
    //                                                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //                          nextLong(min, max) 是 [min, max),传 max+1 才能取到 max
    Instant fireAt = Instant.now().plusMillis(delayMs);

    matchTaskScheduler.schedule(() -> {
        try {
            // 触发前再校验一次 target 仍为 DH(可能注销)
            int type = userServiceClient.getUserType(dhId);
            if (type != UserTypeConst.DH) {
                log.debug("Delayed match skip: target no longer DH userId={} dhId={}", userId, dhId);
                return;
            }
            matchService.createMatch(userId, dhId, MatchSourceConst.SWIPE_MATCH);
        } catch (Exception e) {
            log.error("Delayed match failed userId={} dhId={} err={}", userId, dhId, e.getMessage(), e);
        }
    }, fireAt);
}
```

**调用点**（SwipeService 中 RIGHT DH 路径）：

```java
// dating-server/match-service/src/main/java/com/dating/match/service/SwipeService.java:170-174
} else {
    // target DH → 延迟 match
    // 注意:延迟回调(DhDelayedMatchService)负责后续 createMatch + 副作用
    dhDelayedMatchService.scheduleDelayedMatch(userId, targetUserId);
}
```

**关键观察**：
- `nextLong(min, max+1)` —— 注意左闭右开区间,常见坑
- 触发前**再校验 DH 状态**(可能中途注销) —— 防御性代码
- 失败 catch 不抛 —— 内存任务丢了不影响数据一致性,trade-off 已在 PRD 5.2 接受

---

## 知识点四：match 跨服务副作用的 Outbox 兜底

**核心代码**：`service/MatchService.java` + `service/MatchOutboxService.java` + `scheduler/MatchOutboxRetry.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/service/MatchService.java:53-70
@Transactional(rollbackFor = Exception.class)
public MatchEntity createMatch(long userA, long userB, String source) {
    // 1. INSERT IGNORE match
    MatchManager.InsertResult insertResult = matchManager.insertIgnoreConflictWithLog(userA, userB, source);
    if (!insertResult.success() || insertResult.match() == null) {
        return insertResult.existing();
    }
    MatchEntity match = insertResult.match();

    // 2. 同事务清理双向 like_record(原暗恋升级 match)
    likeRecordManager.softDeleteByPair(userA, userB);

    // 3. 入 outbox 三条副作用(同步失败由后台 retry)
    enqueueSideEffects(match.getId(), userA, userB, source);
    return match;
}

// 75-96: 入 outbox 三类副作用
private void enqueueSideEffects(long matchId, long userA, long userB, String source) {
    // 3.1 EnsureConversation
    MatchOutboxEntity conv = new MatchOutboxEntity();
    conv.setMatchId(matchId);
    conv.setAction(OutboxActionConst.ENSURE_CONVERSATION);
    conv.setPayloadJson(toJson(payloadOfConv(userA, userB)));
    ...
    outboxManager.enqueue(conv);

    // 3.2 SendSystemMessage x2(双方)
    enqueueSystemMsg(matchId, userA, userB, source);
    enqueueSystemMsg(matchId, userB, userA, source);

    // 3.3 TriggerDhOpening(简化:异步判定)
    if (MatchSourceConst.SWIPE_SUPER_HI.equals(source) || MatchSourceConst.SWIPE_MATCH.equals(source)) {
        enqueueDhOpening(matchId, userA, userB);
    }
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/service/MatchOutboxService.java:89-94
// ★★★ 核心:指数退避公式 ★★★
private void scheduleRetry(MatchOutboxEntity task) {
    int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
    long nextDelaySec = (long) Math.min(60 * 30, Math.pow(2, attempts + 1) * 5);
    //                                ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //                  60*30=1800s=30min 封顶;Math.pow(2, attempts+1)*5 指数增长
    Instant nextRetry = Instant.now().plus(Duration.ofSeconds(nextDelaySec));
    outboxManager.markRetry(task.getId(), nextRetry, props.getOutboxMaxAttempts());
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/service/MatchOutboxService.java:59-87
// dispatch switch 分发三种 action
private boolean dispatch(MatchOutboxEntity task) {
    JsonNode payload = parse(task.getPayloadJson());
    switch (task.getAction()) {
        case OutboxActionConst.ENSURE_CONVERSATION -> {
            long userIdA = payload.path("user_id_a").asLong();
            long userIdB = payload.path("user_id_b").asLong();
            String conv = imServiceClient.ensureConversation(userIdA, userIdB);
            ...
            return true;
        }
        case OutboxActionConst.SYSTEM_MSG -> {
            ...
            imServiceClient.sendSystemMessage(toUserId, content, type);
            ...
            return true;
        }
        case OutboxActionConst.DH_OPENING -> {
            // DH 开场白需要 conversation_id — 此处无 conversation_id 时跳到下一轮 retry 时由 EnsureConversation 已完成获得
            log.debug("DH_OPENING placeholder — skip until conv established matchId={}", task.getMatchId());
            return true;
        }
        ...
    }
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/scheduler/MatchOutboxRetry.java:19-20
@Scheduled(fixedDelay = 30_000L)
public void run() {
    int delivered = outboxService.deliver();
    ...
}
```

**指数退避序列（attempts → nextDelaySec）**：
```
attempts=0 → 2^1 *5 = 10s     (但首次 nextRetryAt=now,立即可跑)
attempts=1 → 2^2 *5 = 20s
attempts=2 → 2^3 *5 = 40s
attempts=3 → 2^4 *5 = 80s
attempts=4 → 2^5 *5 = 160s
attempts=5 → 2^6 *5 = 320s
attempts=6 → 2^7 *5 = 640s
attempts=7 → 2^8 *5 = 1280s
attempts=8+ → min(1800, ...) = 1800s = 30min 封顶
```

**关键观察**：
- `dispatch` switch 用 Java 17 switch expression —— `case X -> { ... }`
- DH_OPENING 当前是 placeholder（conversation_id 还没拿到）—— 依赖 EnsureConversation 先完成,后续重试时由 IM 副作用提供 conv id
- `outboxMaxAttempts` 走 `MatchProperties` Nacos 配置,默认 5 次后置 DEAD

---

## 知识点五：match 表 UNIQUE + 重复触发的 ERROR 日志报警

**核心代码**：`manager/MatchManager.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/manager/MatchManager.java:58-78
// ★★★ 显式带日志的插入:成功 / 已存在都打 ERROR ★★★
public InsertResult insertIgnoreConflictWithLog(long userA, long userB, String source) {
    long[] pair = pair(userA, userB);  // low=min(a,b), high=max(a,b)
    MatchEntity entity = new MatchEntity();
    entity.setUserIdLow(pair[0]);
    entity.setUserIdHigh(pair[1]);
    entity.setSource(source);
    entity.setMatchedAt(Instant.now());
    try {
        matchMapper.insert(entity);
        return new InsertResult(true, entity, null);
    } catch (DuplicateKeyException e) {
        MatchEntity existing = matchMapper.findByPair(pair[0], pair[1]);
        log.error("Duplicate match attempt: pair=({}, {}) existing_id={} existing_source={} new_source={} "
                        + "── 上游召回过滤可能存在 bug,排查 user_swipe_history 与召回 exclude_user_ids 链路",
                pair[0], pair[1],
                existing != null ? existing.getId() : null,
                existing != null ? existing.getSource() : null,
                source);
        return new InsertResult(false, existing, existing);
    }
}

// 107-109: pair 工具 — 同一对 (a,b) 与 (b,a) 视为同一
public static long[] pair(long a, long b) {
    return a < b ? new long[]{a, b} : new long[]{b, a};
}
```

**InsertResult 记录结构**（116-117）：
```java
public record InsertResult(boolean success, MatchEntity match, MatchEntity existing) {
    // success=true: 新插入(match 有值,existing=null)
    // success=false: UNIQUE 冲突(match=existing=已存在记录)
}
```

**关键观察**：
- 不抛异常给用户 —— 调用方 `createMatch` 拿到 `success=false` 时直接返回 existing,UX 不受损
- ERROR 日志携带 `existing_source` 与 `new_source` —— **关键排查信号**,能区分"对方真重复 match"还是"重复 source 不同"
- 注意 current 实现**没拿 caller_stack** —— 是已知 gap,改进方案:catch 中 `new Exception().getStackTrace()` 取 top 5 帧

---

## 知识点六：Redis HASH + HINCRBY + 配额回滚模式

**核心代码**：`service/QuotaService.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/service/QuotaService.java:50-76
public void consumeRightSwipe(long userId, int tier) {
    String key = quotaKey(userId);
    int rightLimit = SubscriptionTierConst.dailyRightSwipeLimit(tier);
    int cardLimit  = SubscriptionTierConst.dailyCardLimit(tier);

    // 1. right_swipe
    Long right = stringRedisTemplate.opsForHash().increment(key, F_RIGHT, 1L);
    // 首次写入时设 TTL
    if (right != null && right == 1L) {
        stringRedisTemplate.expire(key, Duration.ofSeconds(props.getQuotaTtlSeconds()));
        //                                                            ^^^^^^^^^^^^^^^^^^^^^^^^^^^
        //                                    36h — 让 UTC 跨日 + 偶尔写入失败有冗余
    }
    if (right != null && right > rightLimit) {
        stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);  // 回滚
        throw new MatchBizException(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED, ...);
    }

    // 2. cards
    Long cards = stringRedisTemplate.opsForHash().increment(key, F_CARDS, 1L);
    if (cards != null && cards > cardLimit) {
        // cards 已超限,回滚两张计数
        stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
        stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);  // 两个字段一起回滚
        throw new MatchBizException(MatchErrorCode.QUOTA_CARDS_EXCEEDED, ...);
    }
}
```

```java
// 85-118: Super Hi 三字段扣减 + 金币回退路径
public SuperHiCharge consumeSuperHi(long userId, int tier, int giftedLimit, int coinPrice) {
    ...
    // 1. cards
    Long cards = stringRedisTemplate.opsForHash().increment(key, F_CARDS, 1L);
    if (cards > cardLimit) {
        stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
        throw new MatchBizException(QUOTA_CARDS_EXCEEDED, ...);
    }
    // 2. right_swipe
    Long right = stringRedisTemplate.opsForHash().increment(key, F_RIGHT, 1L);
    if (right > rightLimit) {
        stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
        stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);  // 两层回滚
        throw new MatchBizException(QUOTA_RIGHT_SWIPE_EXCEEDED, ...);
    }
    // 3. super_hi 赠送配额
    Long gifted = stringRedisTemplate.opsForHash().increment(key, F_SUPER_HI, 1L);
    if (gifted != null && gifted <= giftedLimit) {
        return new SuperHiCharge(0, false);  // 走订阅赠送
    }
    // 超赠送 — 回滚 super_hi,标记走金币
    stringRedisTemplate.opsForHash().increment(key, F_SUPER_HI, -1L);
    return new SuperHiCharge(coinPrice, true);  // 需要金币扣减
}

// 123-127: 金币失败时三层回滚
public void rollbackSuperHi(long userId) {
    String key = quotaKey(userId);
    stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
    stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
}
```

```java
// 184-191: key 格式 + UTC 日期
public static String todayUtc() {
    return LocalDate.now(ZoneOffset.UTC).format(YYYYMMDD);
}
public static String quotaKey(long userId) {
    return "putao:match:quota:" + userId + ":" + todayUtc();
}
```

**关键观察**：
- 分两阶段扣减：先扣 right_swipe（消费级）再扣 cards（基础级）—— RIGHT_SWIPE 语义"消耗 1 张卡 + 1 次右划"
- 失败回滚是 `HINCRBY -1L`,**不是** Java 端先 read 后 write —— 因为 HINCRBY 是 Redis 单线程命令串行,原子
- 注意 SuperHi 三字段扣减顺序是 **cards → right_swipe → super_hi**(与 right_swipe 不同) —— 因为 SuperHi 优先级最高,要保证核心资源先扣
- `todayUtc()` 强制 UTC —— **符合 CLAUDE.md 时区红线**

---

## 知识点七：DH 模拟计划的真实性约束（防穿帮）

**核心代码**：`service/DhInteractionPlanService.java` + `scheduler/OnlinePlanGenerator.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/service/DhInteractionPlanService.java:112-236
private int generateOne(long userId, int scene) {
    // 1. 类型闸:必须 BH
    int type = userServiceClient.getUserType(userId);
    if (type != UserTypeConst.BH) {
        return 0;
    }

    // 2.1 cooldown(仅 ONLINE)
    if (scene == DhInteractionConst.SCENE_ONLINE) {
        Boolean has = stringRedisTemplate.hasKey(MatchRedisKey.dhPlanCooldown(userId));
        if (Boolean.TRUE.equals(has)) {
            return 0;
        }
    } else {
        // OFFLINE:lastScene 闸
        String lastScene = stringRedisTemplate.opsForValue().get(MatchRedisKey.dhPlanLastScene(userId));
        if ("OFFLINE".equals(lastScene)) {
            return 0;
        }
    }
    // 2.2 任务表去重
    if (taskManager.existsByScene(userId, scene)) {
        return 0;
    }

    // 5. 24h 上限检查
    Instant since24h = Instant.now().minus(Duration.ofHours(24));
    long dailyLike = likeRecordManager.countDhLikeSince(userId, since24h);
    long dailyVisit = visitRecordManager.countDhVisitSince(userId, since24h);
    int likeRemaining = (int) Math.max(0, props.getDailyDhLikeCap() - dailyLike);
    int visitRemaining = (int) Math.max(0, props.getDailyDhVisitCap() - dailyVisit);
    if (likeRemaining == 0 && visitRemaining == 0) {
        return 0;
    }

    // 6. 决定本次生成数
    int countRange = scene == DhInteractionConst.SCENE_ONLINE
            ? randomBetween(props.getOnlineCountMin(), props.getOnlineCountMax())    // 5~10
            : randomBetween(props.getOfflineCountMin(), props.getOfflineCountMax()); // 3~6

    // 8. 按 visit/like 比例分配
    int visitTarget = (int) Math.round(picks.size() * props.getVisitRatio());  // 0.6
    visitTarget = Math.min(visitTarget, visitRemaining);
    int likeTarget = picks.size() - visitTarget;
    likeTarget = Math.min(likeTarget, likeRemaining);

    // 9. execute_time 在 [now, now + windowMin] 均匀随机
    int windowMin = scene == DhInteractionConst.SCENE_ONLINE
            ? props.getOnlineExecuteWindowMin()    // 30
            : props.getOfflineExecuteWindowMin(); // 30
    Instant fireStart = Instant.now();
    Instant fireEnd = fireStart.plus(Duration.ofMinutes(windowMin));

    List<DhInteractionTaskEntity> tasks = new ArrayList<>();
    for (int i = 0; i < picks.size() && (visitTarget + likeTarget) > 0; i++) {
        DhCandidate dh = picks.get(i);
        ...
        task.setExecuteTime(randomInstant(fireStart, fireEnd));  // ← ★ 关键防穿帮
        ...
    }
    if (!tasks.isEmpty()) {
        taskManager.batchInsert(tasks);
    }

    // 10. 收尾:cooldown / lastScene
    if (scene == DhInteractionConst.SCENE_ONLINE) {
        stringRedisTemplate.opsForValue().set(
                MatchRedisKey.dhPlanCooldown(userId),
                "1",
                Duration.ofSeconds(props.getOnlineCooldownSeconds()));  // 7200
    }
    stringRedisTemplate.opsForValue().set(
            MatchRedisKey.dhPlanLastScene(userId),
            scene == DhInteractionConst.SCENE_ONLINE ? "ONLINE" : "OFFLINE");
    return tasks.size();
}

// 318-323: randomInstant 均匀分布工具
private Instant randomInstant(Instant start, Instant end) {
    long startMs = start.toEpochMilli();
    long endMs = end.toEpochMilli();
    long randomMs = ThreadLocalRandom.current().nextLong(startMs, endMs + 1);
    return Instant.ofEpochMilli(randomMs);
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/scheduler/OnlinePlanGenerator.java:27-53
@Scheduled(fixedDelay = 60_000L)
public void run() {
    RLock lock = redissonClient.getLock(MatchRedisKey.LOCK_DH_PLAN_ONLINE);
    boolean acquired;
    try {
        acquired = lock.tryLock(0, 60, TimeUnit.SECONDS);  // waitTime=0,抢不到 skip
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }
    if (!acquired) {
        log.debug("OnlinePlanGenerator lock not acquired, skip");
        return;
    }
    try {
        int n = planService.runOnlinePlan();
        ...
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/service/DhInteractionPlanService.java:243-258
// Executor:扫到期任务 + 硬删(成功才删,失败保留行下一轮重试)
public int runExecutor() {
    Instant now = Instant.now();
    List<DhInteractionTaskEntity> due = taskManager.scanDueTasks(now, props.getDhTaskScanLimit());
    int executed = 0;
    for (DhInteractionTaskEntity t : due) {
        try {
            executeOne(t);
            taskManager.hardDelete(t.getId());  // 硬删,成功才删
            executed++;
        } catch (Exception e) {
            log.warn("Execute DH task failed: id={} err={}", t.getId(), e.getMessage());
            // 不删行,下一轮继续重试
        }
    }
    return executed;
}
```

**关键观察**：
- 三道闸顺序：**类型闸 → cooldown/lastScene → 任务表去重** —— 缺一不可
- `randomInstant` 用 `ThreadLocalRandom.nextLong(startMs, endMs+1)` —— 真正均匀分布
- `tryLock(0, ...)` —— scheduler 抢不到锁直接 skip,避免阻塞等下一次调度
- Executor 失败保留行 —— 关键防"任务丢失"措施,重启 / 抖动能补跑
- 注意当前 `execute_window_min = 30` —— Nacos 可改,防穿帮能力靠这个配置
- `like_content_templates` 是 JSON 列表 —— 运营可热刷（`randomLikeContent` 解析 `[{content, genderPref}]`）

---

## 知识点一 + 知识点八：两池召回 + D0/D1 双轨推荐

**核心代码**：`recommend/CandidateRecaller.java` + `recommend/FeedMerger.java` + `recommend/Ranker.java` + `service/ColdStartService.java`

### 1. 两池召回（两池独立，互不混）

```java
// dating-server/match-service/src/main/java/com/dating/match/recommend/CandidateRecaller.java:52-94
// D0 DH 池召回:渐进扩范围 L0→L3,去重累积到目标
public List<DhCandidate> recallDhPoolD0(long userId, int targetGender,
                                        List<Long> excludeUserIds,
                                        int userAge, int userBeauty, String userRace) {
    Set<Long> seen = new LinkedHashSet<>(excludeUserIds == null ? List.of() : excludeUserIds);
    List<DhCandidate> accumulated = new ArrayList<>();
    List<String> levels = props.getColdStartDhLevels();  // ["L0","L1","L2","L3"]

    int[] ageWindow = {props.getColdStartBhAgeWindow()};
    int[] beautyWindow = {props.getColdStartBhBeautyWindow()};
    String[] races = {userRace};

    for (String level : levels) {
        if (accumulated.size() >= POOL_TARGET) {  // POOL_TARGET = 240
            break;
        }
        applyDhLevel(level, ageWindow, beautyWindow, races);  // ★ 渐进改参
        int need = POOL_TARGET - accumulated.size();
        ...
    }
}

// 99-127: 应用渐进层级参数
private void applyDhLevel(String level, int[] ageWindow, int[] beautyWindow, String[] races) {
    switch (level) {
        case "L0" -> { ageWindow[0]=5; beautyWindow[0]=15; races[0]=races[0]; }       // 最严,同人种
        case "L1" -> { ageWindow[0]=5; beautyWindow[0]=15; races[0]=null; }          // 放人种
        case "L2" -> { ageWindow[0]=10; beautyWindow[0]=25; races[0]=null; }         // 扩大
        case "L3" -> { ageWindow[0]=60; beautyWindow[0]=60; races[0]=null; }         // 兜底
    }
}
```

```java
// 169-193: BH 池严格条件一次 — 不够就不够
public List<BhCandidate> recallBhPool(long userId, double radiusKm, ...) {
    NearbyUsersRequest req = NearbyUsersRequest.newBuilder()
            .setUserId(userId)
            .setRadiusKm(radiusKm)
            .setAgeMin(ageMin).setAgeMax(ageMax)
            .setBeautyMin(beautyMin).setBeautyMax(beautyMax)
            .addAllRaces(races)
            .setLastActiveWithinDays(lastActiveWithinDays)
            .addAllExcludeUserIds(excludeUserIds == null ? List.of() : excludeUserIds)
            .setLimit(POOL_TARGET)
            .build();
    List<BhCandidate> batch = userServiceClient.nearbyUsers(req);
    return batch;  // 严格条件一次,不够就不够
}

// 206-214: 异性恋假设
public static int oppositeGender(int gender) {
    if (gender == Gender.GENDER_MALE.getNumber()) {
        return Gender.GENDER_FEMALE.getNumber();
    }
    if (gender == Gender.GENDER_FEMALE.getNumber()) {
        return Gender.GENDER_MALE.getNumber();
    }
    return Gender.GENDER_UNKNOWN.getNumber();
}
```

### 2. 按比例 merge（关键不变量）

```java
// dating-server/match-service/src/main/java/com/dating/match/recommend/FeedMerger.java:83-116
public MergedFeed merge(List<?> bhPool, List<DhCandidate> dhPool, double bhRatio, int queueSize) {
    int targetBh = (int) Math.round(queueSize * bhRatio);  // 240 × 0.40 = 96
    int actualBh = Math.min(targetBh, bhPool.size());       // 严格不够就不够
    int shortBh = targetBh - actualBh;                     // BH 缺口
    int actualDh = (queueSize - targetBh) + shortBh;       // DH 补齐
    actualDh = Math.min(actualDh, dhPool.size());

    List<MergedEntry> merged = new ArrayList<>(queueSize);
    int bhCount = 0;
    int dhCount = 0;
    int step = bhRatio <= 0 ? 1 : (int) Math.max(1, Math.round(1.0 / bhRatio));  // 1:0.4 → step=3

    while (merged.size() < queueSize && (bhCount < actualBh || dhCount < actualDh)) {
        for (int i = 0; i < step && bhCount < actualBh && merged.size() < queueSize; i++) {
            Object bh = bhPool.get(bhCount);
            merged.add(MergedEntry.fromBh(bh));
            bhCount++;
        }
        if (dhCount < actualDh && merged.size() < queueSize) {
            DhCandidate dh = dhPool.get(dhCount);
            merged.add(MergedEntry.fromDh(dh));
            dhCount++;
        }
    }

    return new MergedFeed(merged, ..., actualBh, actualDh);
}

// 119-128: D1 个性化比例(L1 + L2 偏移)
public double computeBhRatioD1(PreferenceProfile pref) {
    double base = props.getD1BhRatio();  // L1 运营基线 0.40
    if (!props.isD1PreferenceEnabled() || pref == null
            || pref.getDhBhRatio() == null
            || pref.getSampleCount() < props.getMinSamples()) {
        return base;  // 样本 < 10 不偏移
    }
    double offset = (0.5 - pref.getDhBhRatio()) * props.getD1PreferenceOffset() * 2;
    offset = Math.max(-props.getD1PreferenceOffset(), Math.min(props.getD1PreferenceOffset(), offset));
    return Math.max(0.0, Math.min(1.0, base + offset));
}
```

### 3. D0 字典序 vs D1 打分公式

```java
// dating-server/match-service/src/main/java/com/dating/match/recommend/FeedMerger.java:48-73
// D0 BH 池 4 级字典序
public List<BhCandidate> sortBhForD0(List<BhCandidate> bh, ...) {
    return bh.stream().sorted((a, b) -> {
        // 1. is_new_bh desc
        boolean an = Boolean.TRUE.equals(isNewBh.apply(a.getUserId()));
        boolean bn = Boolean.TRUE.equals(isNewBh.apply(b.getUserId()));
        if (an != bn) return an ? -1 : 1;
        // 2. same_race_as_user desc
        ...
        // 3. abs(age - user.age) asc
        ...
        // 4. beauty desc
        return Integer.compare(b.getBeautyScore(), a.getBeautyScore());
    }).limit(props.getD1QueueSize()).toList();
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/recommend/Ranker.java:35-54
// D1 BH 池完整打分
public ScoredCandidate scoreBh(BhCandidate c, PreferenceProfile pref,
                                Map<Long, Integer> mutualSwipedMe) {
    double[] w = props.getBhWeights();  // [0.45, 0.30, 0.15, 0.10]
    double prefSim = preferenceSimilarity(c.getAge(), c.getRace(), c.getBeautyScore(), pref);
    double beauty = normalize(c.getBeautyScore());
    double dist = distanceDecay(c.getDistanceKm());
    double activity = activityScore(c.getLastActiveAtMs());

    double base = w[0] * prefSim + w[1] * beauty + w[2] * dist + w[3] * activity;

    double mutualBonus = 0.0;
    if (mutualSwipedMe != null && mutualSwipedMe.containsKey(c.getUserId())) {
        mutualBonus = props.getMutualLikeBonus();  // +0.20
    }
    double newBhBonus = 0.0;
    if (isNewBh(c.getCreatedAtMs(), props.getNewBhWindowDays())) {
        newBhBonus = props.getNewBhBonus();  // +0.20
    }

    return new ScoredCandidate(c.getUserId(), UserTypeConst.BH, base + mutualBonus + newBhBonus);
}

// 70-89: preference_similarity = age 高斯 * beauty 高斯 * race_dist
private double preferenceSimilarity(int age, String race, int beauty, PreferenceProfile pref) {
    ...
    double ageZ = Math.abs((age - pref.getAgeMean()) / Math.max(0.5, pref.getAgeStd()));
    double ageSim = ageZ >= Z_CUTOFF ? 0.0 : Math.exp(-0.5 * ageZ * ageZ);  // Z_CUTOFF=3.0

    double beautyZ = ...;
    double beautySim = beautyZ >= Z_CUTOFF ? 0.0 : Math.exp(-0.5 * beautyZ * beautyZ);

    double raceSim = 0.5;  // 默认中性
    if (race != null && pref.getRaceDist() != null && !pref.getRaceDist().isEmpty()) {
        Double r = pref.getRaceDist().get(race);
        raceSim = r != null ? r : 0.2;  // race 缺失回退 0.2
    }

    return ageSim * beautySim * raceSim;  // 三项乘积
}

// 95-100: distance_decay = exp(-d/50km); DH 距离为负 → 固定 0.5
private double distanceDecay(double distanceKm) {
    if (distanceKm < 0) {
        return 0.5;  // DH 用
    }
    return Math.exp(-distanceKm / 50.0);
}

// 102-109: activity_score = exp(-days/7); 7 天未活跃衰减约 37%
private double activityScore(long lastActiveAtMs) {
    ...
    double days = Duration.between(last, Instant.now()).toDays();
    return Math.exp(-days / 7.0);
}
```

**ColdStartService 完整 D0 链路**：

```java
// dating-server/match-service/src/main/java/com/dating/match/service/ColdStartService.java:45-96
public int buildAndPush(long userId) {
    // 1. 取用户画像(中心点)
    List<UserProfileProto> profiles = userServiceClient.batchGetProfile(List.of(userId));
    UserProfileProto user = profiles.get(0);
    int userGender = user.getGender().getNumber();
    int targetGender = CandidateRecaller.oppositeGender(userGender);

    // 2. exclude:已 swipe target 列表
    List<Long> exclude = recaller.excludeUserIds(userId);

    // 3. DH 池 — 渐进扩范围
    List<DhCandidate> dhPool = recaller.recallDhPoolD0(userId, targetGender, exclude, ...);

    // 4. BH 池 — 严格条件一次
    List<BhCandidate> bhRaw = recaller.recallBhPool(userId, ..., bhExclude);

    // 5. 池内排序(D0 字典序)
    List<BhCandidate> bhSorted = merger.sortBhForD0(bhRaw, ..., newBhWindowDays);
    List<DhCandidate> dhSorted = merger.sortDhForD0(dhPool, null, userAge);

    // 6. 按比例 merge
    MergedFeed merged = merger.merge(bhSorted, dhSorted,
                                     merger.computeBhRatioD0(),  // 0.20(D0 bh_ratio)
                                     props.getD1QueueSize());    // 240

    // 7. RPUSH 写入 Redis LIST
    return pushToRedis(userId, merged.entries());
}

// 101-121: pushToRedis
public int pushToRedis(long userId, List<MergedEntry> entries) {
    ...
    stringRedisTemplate.opsForList().rightPushAll(key, elements);
    stringRedisTemplate.expire(key, Duration.ofSeconds(props.getFeedTtlSeconds()));  // 7d
    ...
}
```

**关键观察**：
- BH 池**从不放宽** —— merge 阶段 DH 自动补齐是关键设计
- DH 渐进 4 层 + `LinkedHashSet` 去重累积 —— 保证"渐进但严格去重"
- `preferenceSimilarity` 三项乘积 —— age 高斯 × beauty 高斯 × race 占比,默认中性值都给了(0.5 / 0.2)
- `Z_CUTOFF=3.0` —— |z|>3 视为 0,截断防止无意义计算
- D0 BH 池字典序第 1 级 `is_new_bh` —— 是 D1 `new_bh_bonus` 的 D0 等价物
- 注意 `excludeUserIds` 包含 `user_swipe_history` 所有方向 —— "看过的人不再出现"的权威过滤

---

## 知识点九：跨服务 gRPC 边界严守（CLAUDE.md 红线落地）

**核心代码**：`client/UserServiceClient.java` + `client/ImServiceClient.java` + `client/PaymentServiceClient.java`

```java
// dating-server/match-service/src/main/java/com/dating/match/client/UserServiceClient.java:42-57
// ★★★ 失败降级返回空 ★★★
public List<UserProfileProto> batchGetProfile(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
        return Collections.emptyList();
    }
    try {
        BatchGetProfilesRequest req = BatchGetProfilesRequest.newBuilder()
                .addAllUserIds(userIds)
                .setIncludeInterests(false)
                .build();
        BatchGetProfilesResponse resp = userServiceBlockingStub.batchGetProfile(req);
        return resp.getProfilesList();
    } catch (Exception e) {
        log.warn("user-service.batchGetProfile failed, count={}, err={}", userIds.size(), e.getMessage());
        return Collections.emptyList();  // ← 失败降级,不抛
    }
}

// 62-71: getUserType 失败返 -1,调用方决定 fallback
public int getUserType(long userId) {
    try {
        GetUserTypeRequest req = GetUserTypeRequest.newBuilder().setUserId(userId).build();
        GetUserTypeResponse resp = userServiceBlockingStub.getUserType(req);
        return resp.getUserType().getNumber();
    } catch (Exception e) {
        log.warn("user-service.getUserType failed, userId={}, err={}", userId, e.getMessage());
        return -1;
    }
}
```

```java
// dating-server/match-service/src/main/java/com/dating/match/client/ImServiceClient.java:81-94
// listOnlineUsers 失败返空集合
public List<Long> listOnlineUsers(long sinceMs, long untilMs, int limit) {
    try {
        ...
        return resp.getUserIdsList();
    } catch (Exception e) {
        log.warn("im-service.listOnlineUsers failed, err={}", e.getMessage());
        return Collections.emptyList();
    }
}
```

**关键观察**：
- **失败降级返回空集合/默认值**,**不抛**给上层 —— match 主流程不会因 user-service / im-service 抖动挂掉
- `ensureConversation` / `sendSystemMessage` / `triggerDhOpening` 故意**不**做 try-catch —— 让异常向上抛 → 进入 outbox 重试路径(参见 MatchOutboxService.dispatch)
- 三类调用分类:
  - **读**: `batchGetProfile` / `getUserType` / `listDhCandidates` / `nearbyUsers` / `listOnlineUsers` / `listRecentOfflineUsers` —— 失败降级
  - **写(主路径可失败)**: 无 —— match-service 写自己的 PG 表,不跨服务写
  - **副作用(写 IM)**: `ensureConversation` / `sendSystemMessage` / `triggerDhOpening` —— 失败抛异常 → outbox 兜底

---

## 知识点十：match.source 用 String 而非 Java enum

**核心代码**：`constant/MatchSourceConst.java`(Java 端字符串常量)

```java
// 仅看调用侧
// dating-server/match-service/src/main/java/com/dating/match/service/SwipeService.java:157
matchManager.insertIgnoreConflictWithLog(
        userId, targetUserId, com.dating.match.constant.MatchSourceConst.SWIPE_MATCH);
//                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                                  String "SWIPE_MATCH",不是 enum

// dating-server/match-service/src/main/java/com/dating/match/service/DhDelayedMatchService.java:63
matchService.createMatch(userId, dhId, MatchSourceConst.SWIPE_MATCH);
```

**MatchEntity 字段**:
- `match.source VARCHAR(30) NOT NULL` —— SQL 用 VARCHAR,不用 PG enum
- Java 端用 `static final String SWIPE_MATCH = "SWIPE_MATCH";` 常量,不用 enum

**关键观察**:
- 新增 TBD 入口(推荐位 / 活动 / 系统配对)时**只改 Nacos 配置 + 加常量**,**不**跑 PG migration
- 缺点:编译期校验不到非法值 —— 集中管理在 `MatchSourceConst` 配合 code review

---

## 知识点十一：性别反推(异性恋假设)

**核心代码**:`recommend/CandidateRecaller.java` `oppositeGender`

```java
// dating-server/match-service/src/main/java/com/dating/match/recommend/CandidateRecaller.java:206-214
public static int oppositeGender(int gender) {
    if (gender == Gender.GENDER_MALE.getNumber()) {
        return Gender.GENDER_FEMALE.getNumber();
    }
    if (gender == Gender.GENDER_FEMALE.getNumber()) {
        return Gender.GENDER_MALE.getNumber();
    }
    return Gender.GENDER_UNKNOWN.getNumber();  // 未指定性别,拿不到任何候选
}
```

**调用点**:
```java
// ColdStartService.java:54-55
int userGender = user.getGender().getNumber();
int targetGender = CandidateRecaller.oppositeGender(userGender);

// DhInteractionPlanService.java:143
int targetGender = CandidateRecaller.oppositeGender(user.getGender().getNumber());
```

**关键观察**:
- 全平台异性恋假设 —— 是产品决策,**不**是技术决策
- `GENDER_UNKNOWN` 时该用户拿不到任何候选 —— 没需求方在召回阶段兜底

---

## 知识点十二:BH/DH 双用户体系(产品视角一致 + 内部埋点)

**核心代码**:proto `LikeVO` / `VisitVO` 字段设计

```protobuf
// proto/match/match.proto(伪代码)
message LikeVO {
    int64 from_user_id = 1;          // liker
    string nickname = 2;
    int32 age = 3;
    repeated string photo_keys = 4;
    int64 liked_at_unix_ms = 5;
    string like_content = 6;
    // 不下发 from_user_type / source —— 对外屏蔽 BH/DH 差异
}

message VisitVO {
    int64 from_user_id = 1;
    ...
    int32 visit_count = 6;
    // 同样不下发 from_user_type / source
}
```

**后端埋点**:
```java
// dating-server/match-service/src/main/java/com/dating/match/service/DhInteractionPlanService.java:265-274
// Executor 落 like_record / visit_record 时,带 from_user_type=DH
if (t.getAction() == DhInteractionConst.ACTION_LIKE) {
    likeRecordManager.upsert(t.getFromUserId(), t.getToUserId(),
            UserTypeConst.DH, sourceCode, t.getLikeContent());
} else if (t.getAction() == DhInteractionConst.ACTION_VISIT) {
    visitRecordManager.upsert(t.getFromUserId(), UserTypeConst.DH,
            t.getToUserId(), sourceCode);
}
```

**关键观察**:
- BH 真人来源 → `LikeVisitSourceConst.SWIPE_RIGHT` (source=1)
- DH ONLINE 计划来源 → `LikeVisitSourceConst.DH_PLAN_ONLINE` (source=2)
- DH OFFLINE 计划来源 → `LikeVisitSourceConst.DH_PLAN_OFFLINE` (source=3)
- VO 不下发 from_user_type / source —— 用户视角"某某 like 了你",App 端不暴露 BH/DH 差异

---

## 附录:核心数字速查(行内引用)

| 数字 | 含义 | 出现位置 |
|------|------|---------|
| **240** | 队列容量 | `CandidateRecaller.POOL_TARGET` (line 40) / `FeedMerger.merge(queueSize)` |
| **15s ~ 2min** | DH 延迟窗口 | `DhDelayedMatchService.MIN_DELAY_MS=15_000, MAX_DELAY_MS=120_000` (line 26-29) |
| **30s** | outbox 重试间隔 | `MatchOutboxRetry.fixedDelay = 30_000L` (line 19) |
| **60s / 20min / 60s** | DH 计划 scheduler 间隔 | `OnlinePlanGenerator.fixedDelay=60_000`, `OfflinePlanGenerator.fixedDelay=1_200_000`, `LikeVisitorTaskExecutor.fixedDelay=60_000` |
| **0.45 / 0.30 / 0.15 / 0.10** | base 权重 | `MatchProperties.bh_weights` (Nacos),`Ranker.scoreBh` 读取 |
| **+0.20 / +0.20** | mutual / new_bh bonus | `MatchProperties.mutualLikeBonus` / `newBhBonus` (Nacos) |
| **5 / 80 / 120 / 120** | 各档每日卡上限 | `SubscriptionTierConst.dailyCardLimit(tier)` |
| **5 / 10 / 15 / 15** | 各档每日右划上限 | `SubscriptionTierConst.dailyRightSwipeLimit(tier)` |
| **0 / 0 / 1 / 1** | 各档每日 Super Hi 赠送 | `SubscriptionTierConst.dailySuperHiLimit(tier)` |
| **100** | Super Hi 金币价 | `SubscriptionTierConst.SUPER_HI_COIN_PRICE` |
| **36h / 7d / 24h** | quota / feed / pref TTL | `props.quotaTtlSeconds` / `props.feedTtlSeconds` / `props.prefTtlSeconds` |
| **5 ~ 10 / 3 ~ 6** | ONLINE / OFFLINE 单次生成 DH 数 | `props.onlineCountMin/Max` / `props.offlineCountMin/Max` |
| **15 / 25** | 24h DH like / visit 上限 | `props.dailyDhLikeCap` / `props.dailyDhVisitCap` |
| **7200 / 1200 / 10800** | ONLINE cooldown / offline 阈值 / lookback | `props.onlineCooldownSeconds` / `props.offlineThresholdSeconds` / `props.offlineLookbackSeconds` |
| **30 / 30** | ONLINE / OFFLINE execute_window_min | `props.onlineExecuteWindowMin` / `props.offlineExecuteWindowMin` |

---

## 关键调用链速查(画图用)

```
[mobile-gw] ──gRPC──► [MatchGrpcService]
                          │
                          ▼
                       [Controller]
                          │
                          ▼
                       [Service]
                          │
                          ├──► [Manager] ──► [Mapper] ──► [PG]
                          │       │
                          │       └──► [StringRedisTemplate] ──► [Redis]
                          │
                          └──► [Client] ──► [UserService gRPC] / [ImService gRPC] / [PaymentService gRPC]
                              │
                              └──► [UserServiceClient] / [ImServiceClient] / [PaymentServiceClient]
                                       │
                                       └──► [blockingStub.batchGetProfile / ...]

[Scheduler] ──► [Service / 调 Outbox 投递 / DH 计划生成 / D1 cron]
```

---

**核对清单**(对照 `interview-qa.md` 12 道高频问题,确认每题都有代码证据):

- [x] Q1 (match-service 介绍) — 代码地图见 README.md
- [x] Q2 (D0/D1 区别) — `ColdStartService` + `FeedMerger.sortBhForD0` + `Ranker.scoreBh`
- [x] Q3 (DH 延迟 15s-2min) — `DhDelayedMatchService.scheduleDelayedMatch` (line 49-69)
- [x] Q4 (Outbox 兜底) — `MatchService.createMatch` (line 53-70) + `MatchOutboxService.scheduleRetry` (line 89-94)
- [x] Q5 (打分公式) — `Ranker.scoreBh` + `preferenceSimilarity` (line 35-89)
- [x] Q6 (DH 防穿帮) — `DhInteractionPlanService.generateOne` (line 112-236)
- [x] Q7 (match UNIQUE) — `MatchManager.insertIgnoreConflictWithLog` (line 58-78)
- [x] Q8 (Redis LIST 一致性) — `FeedService` while 循环 + `markSwiped` (line 80-126 + 158-161)
- [x] Q9 (跨服务一致性) — 3 个 Client 失败降级 + outbox 兜底
- [x] Q10 (分布式锁) — `OnlinePlanGenerator` `tryLock(0, 60, ...)` + `SwipeService` `tryLock(5, 3, ...)`
- [x] Q11 (Redis 数据结构) — `quotaKey` (QuotaService line 189) + `MatchRedisKey.feed/swiped/dhPlan*`
- [x] Q12 (SuperHi vs Swipe) — `QuotaService.consumeSuperHi` (line 85-118) + `SwipeService.writeHistoryAndTrigger`
