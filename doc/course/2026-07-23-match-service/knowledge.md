# match-service 知识点整理

> 配套：[`README.md`](./README.md)、[`prd.md`](./prd.md)、[`interview-qa.md`](./interview-qa.md)
>
> 本文按"场景 → 问题 → 方案 → 权衡 → 实现"结构组织。每个知识点都给出 match-service 真实代码引用 + 为什么这么设计的解释。

---

## 知识点一：两池独立召回 + 按比例 merge 的推荐模型

### 背景/场景

dating app 首页要求每天给 BH 用户稳定产出 240 张可消费卡片。但自然召回结果质量参差：
- **DH 池**充足但需要"个性化找人"（不是随机塞 240 个 DH 就完事）；
- **BH 池**质量好但量级不可控（用户身边未必有 240 个符合条件的真人）。

### 问题分析

如果用"单池一锅出"的模型：
- 质量问题：真人质量参差会让推荐列表质量变低；
- 量级问题：真人凑不齐时会强行放宽条件（损失推荐质量）；
- 扩展问题：新加召回源（"我访问过的人"）要重写整个 merge 逻辑。

### 解决方案

D0 / D1 共用的统一模型：**两池独立召回 → 池内排序 → 按比例 merge → BH 不足 DH 补齐**。

```java
// FeedMerger.merge
public MergedFeed merge(List<?> bhPool, List<DhCandidate> dhPool, double bhRatio, int queueSize) {
    int targetBh = (int) Math.round(queueSize * bhRatio);  // 240 × 0.4 = 96
    int actualBh = Math.min(targetBh, bhPool.size());       // 严格不够就不够
    int short_   = targetBh - actualBh;                     // BH 缺口
    int actualDh = (queueSize - targetBh) + short_;         // DH 补齐
    // 交错插入：每 step 张 DH 间塞 1 张 BH
    for (...) {
        if (bh剩余) add bh; if (dh剩余) add dh;
    }
}
```

### 实现细节

- BH 池：**严格条件一次召回**，不够就不够（merge 阶段 DH 补齐），绝不靠放宽 BH 条件凑数 — 引入低质量真人体验更差。
- DH 池：渐进扩范围 L0~L3，强制凑满 240。
- merge 输出总长度**恒等于 `queueSize`**，从不缩短。

### 权衡取舍

- **为什么 BH 不放宽？** 真人质量是产品体验核心，宁可少真人也不能放进低质真人。
- **为什么 DH 渐进？** DH 量级足够，但"渐进扩范围"在凑数的同时仍保留一定的同质性（L0 仍同人种 / 紧年龄窗口）。
- **为什么用 interleave 而不是 FH 段？** 间隔插入保证每次滑动前几张内 BH/DH 不连刷（避免"连续 5 张都是机器人"）。

---

## 知识点二：Redis LIST + LPOP 即消费的队列模型

### 背景/场景

D0/D1 队列要支持：
- 高频读（每用户每天拉 20+ 次 feed）；
- 追加（队列空了需要重建）；
- 部分过期（D1 cron 触发时**覆盖式重写**）。

### 问题分析

常见方案对比：

| 方案 | 优点 | 缺点 |
|------|------|------|
| PG 表 `user_daily_queue` | 持久化、可 JOIN | 高频读 latency 高，删除/写入用主键散布导致 fragmentation |
| ZSet | score 排序 + O(logN) | LPOP 拿不到 score，需要另行维护 |
| Redis LIST + LPOP | O(1) 消费 + LPOP count 支持批量 | LIST 内只保证不重复消费，**不保证**"消费快照与召回快照一致" |

### 解决方案

**Redis LIST + LPOP count** 主消费 + **`match:swiped` SET 二次过滤** 兜底。

```java
// FeedService.getTodayFeed (简化)
while (result.size() < need) {
    List<String> batch = redis.opsForList().leftPop(feedKey, batchSize);  // Redis 7.0 支持 count
    if (batch.isEmpty()) {
        coldStartService.buildAndPush(userId);  // 队列空 → 实时重建
        batch = redis.opsForList().leftPop(feedKey, batchSize);
    }
    // 二次过滤：LPOP 出的卡片可能已被 swipe（另一台设备并发 swipe 过）
    Set<Long> swipedHits = redis.opsForSet().isMember(swipedKey, targetIds);
    result.addAll(batch 中没被 swipe 过的元素);
}
```

### 实现细节

- **LPOP 即消费**：Redis 7.0 LPOP 支持 `count` 参数，一次拿 N 个。
- **`match:swiped:<user>` SET 同步 SADD**：每次 swipe 接口成功后调 `feedService.markSwiped`。
- **二次过滤兜底**：覆盖"召回快照过时"（用户在另一台设备刚刚 swipe 过同一 target）。
- **没有"已下发但未 swipe"的 SET 维护**：被 LPOP 但未 swipe 的卡允许下次重建重新进入候选池（不算重复推荐 — 用户真的还没看过）。

### 权衡取舍

- **为什么不用单独的 "seen SET"？** LPOP 即弹出本身保证不重复；二次过滤只防"并发另一设备的 swipe 已让对方被消费过"，由 swiped SET 兜底就够。
- **为什么 Redis LIST 没有 seen SET？** seen SET 占内存不小，用户的 swipe_history（PG）才是权威；swiped SET 只是快速查询 cache。
- **LPOP "浪费"卡片**：被二次过滤丢弃的卡片从 LIST 永久弹出。这是接受的代价 — 极少发生，且该卡确实不应该再出现。

---

## 知识点三：DH 延迟匹配（15s ~ 2min 进程内调度）

### 背景/场景

BH 用户右划 DH（数字人），如果一秒内 DH 就来打招呼，用户立刻意识到"这是机器人"。但等太久（>分钟级）用户已经划走下一张，丧失"互相喜欢"的即时反馈价值。

### 问题分析

匹配天然分两类：
- **BH↔BH 互划**：即时 match — 用户和真人都期待立即收到反馈；
- **BH→DH 右划**：产品意图是"让她喜欢我"，但**秒级响应穿帮**。
- **SuperHi 对 DH**：用户**付了费**就期望立即结果，产品意图覆盖防穿帮。

如果都走同一条"立即 match"路径，DH 右划就一眼穿帮；如果都走延迟，付费用户的 SuperHi UX 不达标。

### 解决方案

**进程内 Spring `TaskScheduler.schedule()`** — 15s~2min 均匀随机分布。

```java
// DhDelayedMatchService.scheduleDelayedMatch
public void scheduleDelayedMatch(long userId, long dhId) {
    long delayMs = ThreadLocalRandom.current().nextLong(15_000, 120_001);
    Instant fireAt = Instant.now().plusMillis(delayMs);
    matchTaskScheduler.schedule(() -> {
        try {
            int type = userServiceClient.getUserType(dhId);  // 触发前再校验
            if (type != UserTypeConst.DH) return;
            matchService.createMatch(userId, dhId, SWIPE_MATCH);
        } catch (Exception e) {
            log.error("Delayed match failed ...", e);
        }
    }, fireAt);
}
```

### 实现细节

- 最小延迟 15s（下限保证"不是一秒回应"），最大 2min（上限保证用户在 App 内能看到）。
- 触发前**再校验一次** DH 仍是 DH（可能中途注销）。
- 失败 catch 不抛 — 内存任务丢了不影响（重启丢任务 trade-off）。

### 权衡取舍

#### 为什么用进程内 TaskScheduler 而非 Redis ZSet / PG 表 + cron？

| 维度 | TaskScheduler | Redis ZSET | PG 表 + cron |
|------|---------------|------------|---------------|
| 实现复杂度 | 低 | 中（要 listener） | 高（要表 + 扫表 cron） |
| 持久化 | 无 | 有 | 有 |
| 精度 | μs 级 | ms 级 | 秒级（cron 间隔） |
| 容量 | 10k+ 任务轻松 | 数十万 | 不限 |
| 重启丢任务 | 是 | 否 | 否 |

**选择 TaskScheduler 的关键理由**：
1. **窗口短**（15s-2min）不值得建 PG 表。
2. **量级小**：10k DAU × 5 右划/天 × 50% DH = 25k/天 ≈ 0.3/秒；任何时刻 in-flight < 600。
3. **重启丢任务的成本可接受** — 用户感知是"她没喜欢我"，符合 dating app 常见预期。

#### 为什么 SuperHi 对 DH 不延迟？

用户付费（订阅赠送 / 100 金币）就期望**立即**结果。如果延迟被防穿帮覆盖，付费用户的 UX 反而受损。**产品意图 > 防穿帮**。

---

## 知识点四：match 跨服务副作用的 Outbox 兜底

### 背景/场景

每次创建 match 都要：
1. **本地事务**写 `match` 表 + 删除双向 `like_record`；
2. **远程副作用**调 im-service 建 IM 会话、发系统消息、DH 开场白。

跨服务 RPC 一旦失败，**不能**让用户 swipe RPC 返回错误 — 用户的"匹配"已经被落库，只差几行副作用。

### 问题分析

常见跨服务容错方案：

| 方案 | 实现 | 缺点 |
|------|------|------|
| 本地消息表 + 定时扫表 | PG 表存待发消息 | 需设计表、扫表 cron、与主事务原子性 |
| RocketMQ 事务消息 | 事务型 MQ | 增加 MQ 依赖，消息可能堆积 |
| **Outbox 模式** | PG 表 + 异步重试 | 需调度 worker、指数退避、避免重复消费 |

match-service 选了 **Outbox 模式**。

### 解决方案

**本地事务内写 match + 入 outbox；后台 worker 周期性投递 outbox**。

```java
// MatchService.createMatch（核心）
@Transactional(rollbackFor = Exception.class)
public MatchEntity createMatch(long userA, long userB, String source) {
    MatchManager.InsertResult insertResult = matchManager.insertIgnoreConflictWithLog(...);
    if (!insertResult.success()) {
        return insertResult.existing();   // 已存在的情况不重发副作用
    }
    likeRecordManager.softDeleteByPair(userA, userB);   // 同事务清理
    enqueueSideEffects(match.getId(), userA, userB, source);  // 写 4 条 outbox 任务
    return match;
}

// 写 outbox（4 条任务）
private void enqueueSideEffects(long matchId, long userA, long userB, String source) {
    outboxManager.enqueue(action=ENSURE_CONVERSATION, payload={uid_a, uid_b});
    outboxManager.enqueue(action=SYSTEM_MSG, payload={to: ua, content: ..., type: MATCH_CREATED});
    outboxManager.enqueue(action=SYSTEM_MSG, payload={to: ub, content: ..., type: MATCH_CREATED});  // 双方各一条
    outboxManager.enqueue(action=DH_OPENING, payload={...});  // DH 端触发 AI 开场白
}
```

```java
// MatchOutboxService.deliver（简化）
public int deliver() {
    List<MatchOutboxEntity> pending = outboxManager.listPending(now, scanLimit);
    for (MatchOutboxEntity task : pending) {
        if (dispatch(task)) {        // 调 IM gRPC
            outboxManager.markDone(task.getId());
        } else {
            scheduleRetry(task);      // 指数退避 5s → 10s → 20s → ... → 30min 封顶
        }
    }
}

private void scheduleRetry(MatchOutboxEntity task) {
    int attempts = task.getAttempts();
    long nextDelaySec = Math.min(60 * 30, Math.pow(2, attempts + 1) * 5);
    outboxManager.markRetry(task.getId(), now + nextDelaySec, max_attempts);
}

// MatchOutboxRetry scheduler 每 30 秒跑
@Scheduled(fixedDelay = 30_000L)
public void run() {
    outboxService.deliver();
}
```

### 实现细节

- **EnsureConversation 用 im-service 的幂等建会话 RPC** — 即使重复执行不会出错。
- **DH_OPENING 时机**依赖 EnsureConversation 先完成 — 当前实现用 placeholder 完成标志，等 EnsureConversation 完成后下一轮重试再触发。
- **指数退避**：5s → 10s → 20s → 40s → 80s → 160s → 320s → 600s（30 min 封顶）。
- **`max_attempts = 5`**（默认）后置 `DEAD`，需要人工/工具捞数据。

### 权衡取舍

#### 为什么不用 RocketMQ？

- 增加基建依赖（match-service 之前只用 PG + Redis）；
- 副作用消息**不是高吞吐**（match 频率有限）；
- 失败需要重试，MQ 本身就需要一个 worker（Outbox 模式也一样需要 worker）。

#### 为什么不用分布式事务（Saga / TCC）？

- 分布式事务复杂度高，对一个"建会话 + 发消息"这种轻量副作用过度设计；
- IM 副作用**幂等**，天然支持重试 — 不需要事务型一致性。

#### 为什么 outbox 不用枚举而是 String？

- 后续新增 TBD（"推荐位 / 活动入口 / 系统配对"）时改一次 enum 跑一次 migration 太重。
- `action` 字段用 String，dispatch 用 switch 升级简单。

---

## 知识点五：match 表 UNIQUE + 重复触发的 ERROR 日志报警

### 背景/场景

理论上同一对 `(a, b)` 不应该出现第二次 match：
1. 召回阶段已过滤 `user_swipe_history`，任何 LEFT/RIGHT/SUPER_HI 过的对方都不会再出现；
2. swipe 接口本身幂等（同 user/target 第二次返回上次结果）。

但仍要做**防御性兜底** — 如果上游召回过滤 bug / 并发竞态让用户 swipe 了已匹配的对方。

### 问题分析

期望行为：
- **不抛异常给用户**（swipe RPC 不能因 bug 报错）；
- **必须留可观测性信号**（出现 → 说明召回链路有 bug，需要排查）。

### 解决方案

**`match` 表 `(user_id_low, user_id_high)` UNIQUE + INSERT IGNORE + 触发冲突时 ERROR 日志 + 返回 existing**。

```java
// MatchManager.insertIgnoreConflictWithLog
public InsertResult insertIgnoreConflictWithLog(long userA, long userB, String source) {
    long[] pair = pair(userA, userB);   // min/max
    MatchEntity entity = new MatchEntity();
    entity.setUserIdLow(pair[0]);
    entity.setUserIdHigh(pair[1]);
    entity.setSource(source);
    try {
        matchMapper.insert(entity);
        return new InsertResult(true, entity, null);
    } catch (DuplicateKeyException e) {
        MatchEntity existing = matchMapper.findByPair(pair[0], pair[1]);
        log.error("Duplicate match attempt: pair=({},{}) existing_id={} existing_source={} new_source={} ──"
                + " 上游召回过滤可能存在 bug,排查 user_swipe_history 与召回 exclude_user_ids 链路",
            pair[0], pair[1], existing.getId(), existing.getSource(), source);
        return new InsertResult(false, existing, existing);
    }
}
```

### 实现细节

- **主键 (low, high)**：`low = min(a,b), high = max(a,b)` — 同一对 `(a,b)` 与 `(b,a)` 视为同一。
- **DuplicateKeyException 捕获**：不向上抛，而是打 ERROR 日志带丰富上下文（pair / existing_id / existing_source / new_source）。
- **返回 existing match**：调用方仍能拿到合法的 match id，UX 正常。

### 权衡取舍

#### 为什么不直接抛异常？

如果抛异常，调用方可能是 swiper、SuperHi 执行者，**用户会看到错误**。这个 bug 出现率本来就低（正常情况不应触发），用户 UX 受损 + 运营收到 oncall 是双输。

#### 为什么不静默吞掉？

ERROR 日志是召回过滤 bug 的唯一信号。没有它，bug 长期存在但无人知晓。
而且日志带 caller_stack（如果加上）可以快速定位是哪个路径漏过滤。

---

## 知识点六：Redis HASH + HINCRBY + 配额回滚模式

### 背景/场景

每日配额要原子累加 + 超限回滚。常见方案对比：

| 方案 | 优点 | 缺点 |
|------|------|------|
| PG `user_daily_quota` 表 | 持久化强 | 高频 UPDATE 单行、流量大 |
| Redis SET / String 单字段 | 简单 | 多字段要多个 key |
| **Redis HASH + HINCRBY** | 原子、多字段共享 key | 需要手动补 TTL |

match-service 选了 **Redis HASH**。

### 解决方案

```java
// QuotaService.consumeRightSwipe
public void consumeRightSwipe(long userId, int tier) {
    String key = "putao:match:quota:" + userId + ":" + today();
    int rightLimit = SubscriptionTierConst.dailyRightSwipeLimit(tier);
    int cardLimit  = SubscriptionTierConst.dailyCardLimit(tier);

    // 1. 先扣 right_swipe
    Long right = redis.opsForHash().increment(key, "right_swipe", 1L);
    if (right == 1L) redis.expire(key, 36h);   // 首次写入时设 TTL

    if (right > rightLimit) {
        redis.opsForHash().increment(key, "right_swipe", -1L);   // 回滚
        throw new MatchBizException(QUOTA_RIGHT_SWIPE_EXCEEDED);
    }

    // 2. 再扣 cards（right 已落定后才能成功，否则右划配额没问题也会浪费一次 cards）
    Long cards = redis.opsForHash().increment(key, "cards", 1L);
    if (cards > cardLimit) {
        redis.opsForHash().increment(key, "cards", -1L);
        redis.opsForHash().increment(key, "right_swipe", -1L);   // 两个字段一起回滚
        throw new MatchBizException(QUOTA_CARDS_EXCEEDED);
    }
}
```

### 实现细节

- **首次 HINCRBY 后置 TTL**：避免每次 SET 都设 TTL（减少 RTT）。
- **分两阶段扣减**：先扣 right_swipe（消费级）再扣 cards（基础级）。这是 RIGHT_SWIPE 的语义（消耗 1 张卡 + 1 次右划）。
- **失败回滚**：超限 `HINCRBY -1` 立刻回滚 — 配合 Redisson 锁保证没有"两个人同时超限回滚把计数减过头"的问题。
- **接受最差丢 1s 写入**：Redis AOF `appendfsync everysec`，极端宕机最坏丢 1s 配额数据 — 让用户至多多刷几张卡，**不做 PG 强校验**。

### 权衡取舍

#### 为什么 Redis HASH 而不是 PG 表？

- 配额每秒多次读写，PG UPDATE 短时间会撑爆；
- 配额"丢 1s 写入"代价极低（让用户多刷几张卡）；
- 不值得为它维护"PG 表 + 双写 + EOD reconcile 链路"。

#### 为什么 Redisson 锁兜底不用 SET NX？

Swipe 接口已经在 `lock:match:swipe:<user>:<target>` 内串行化（5s TTL），所以 HINCRBY 的 race 已经被锁覆盖。极端情况下，**两个用户给同一个 userId 扣配额**仍然可能并发（多线程 RPC），但单个 userId 单次操作上是 HINCRBY 原子。

#### 为什么不在 Java 端先 read 后 write？

`HGET → 判断 → HINCRBY` 不是原子的，可能两个并发请求都看到 right=4 后都 +1，结果 right=6 而非预期的 5。
HINCRBY → 判断 → HINCRBY 回滚是原子的（同一 key 同字段的 INCR 是 Redis 单线程命令串行）。

---

## 知识点七：DH 模拟计划的真实性约束（防穿帮）

### 背景/场景

新注册 / 长期没真人互动的 BH 用户打开 App，看到 Likes of me / Visits of me 完全是空的，**几秒钟就流失了**。需要给一个"涓涓细流"的关注感，但又不能太密集、不能让用户察觉是机器人。

### 问题分析

如果直接每分钟给 100 个 DH like，再加 visit 系统通知：
- 用户意识到"为什么都是我从未访问的人疯狂喜欢我"；
- DH 集中刷会让产品感觉"假"。

### 解决方案

**三个 scheduler 分工 + 8+ 项 Nacos 可调约束**：

```
                ┌─ OnlinePlanGenerator (1 min)  ─┐
im:presence     │  ListOnlineUserIds 游标扫描     │      ┌─ dh_interaction_task
    online  ────┤  cooldown 2h / 5~10 张每 sweep  │ ───► │  execute_time [now, now+30min]
                └──────────────────────────────  ┘      └─ LikeVisitorTaskExecutor (1 min)
                                                           - LIKE → upsert like_record
                                                           - VISIT → upsert visit_record
                ┌─ OfflinePlanGenerator (20 min) ─┐
user_online_    │  ListRecentOfflineUsers 游标扫描 │
   session ─────┤  last_scene OFFLINE 闸          │ ───► 同上
                │  3~6 张每 sweep                 │
                └──────────────────────────────  ┘
```

### 关键约束（全部 Nacos 实时可调）

| 维度 | 约束 | 防穿帮原理 |
|------|------|-----------|
| 单次生成 DH 数 | 5~10 / 3~6 | 太多就显得批量刷 |
| 24h DH like 上限 | 15 | 防止一天积累几十条 DH like |
| 24h DH visit 上限 | 25 | 同上 |
| VISIT/LIKE 比例 | 60% / 40% | visit 是更日常的行为，like 给得太频反而不真 |
| cooldown / last_scene 闸 | 2h / 单次离线期 1 个 | 不重复骚扰同一用户 |
| execute_time 均匀 30min 分布 | 关键 | "陆陆续续触发"模拟真人持续节奏，**不能密集** |
| like_content 文案池 | JSON 列表 | 文案不重复，运营可热刷 |
| exclude 同 (DH→BH) 已 swipe/match | UPSERT UNIQUE | 同一 DH 不会反复给同一 BH like/visit |

### 实现细节

```java
// DhInteractionPlanService.generateOne
int countRange = scene == ONLINE
    ? rand(5, 10)
    : rand(3, 6);

List<DhCandidate> dhs = listDhCandidates(...);  // 单层召回数 * 2
Collections.shuffle(dhs);
List<DhCandidate> picks = dhs.subList(0, min(countRange, dhs.size()));

int visitTarget = round(picks.size() * 0.6);
int likeTarget  = picks.size() - visitTarget;

// execute_time 在 [now, now + 30min] 均匀随机（关键！）
Instant fireStart = Instant.now();
Instant fireEnd = fireStart.plus(Duration.ofMinutes(30));
for (DhCandidate dh : picks) {
    task.setExecuteTime(randomInstant(fireStart, fireEnd));   // ← 关键
}
```

### 权衡取舍

#### 为什么 BH 端 DH like 上限比 visit 低？

- Like 信息密度更高（带文案/头像），重复出现会"假"；
- Visit 比较日常，每天 25 条更容易接受。

#### 为什么不是"一次塞 30 个"？

防穿帮。30 个 DH 同时出现会立即让用户意识到是 bot。

#### execute_time 为什么必须均匀分布？

**关键反穿帮措施**。如果 10 个任务都 execute_time = now，下一分钟 executor 一次性 insert 10 条 like_record，**用户在 1 分钟内看到 10 个新 like** —— 一眼穿帮。
均匀分散到 30 分钟内，每次打开 App 只看到 1~2 条新互动，符合真人行为。

#### 为什么 cooldown / last_scene 闸为 2h / 单次离线期 1 个？

- ONLINE cooldown 2h：用户每过 2h 才有机会收到一批 drip，模拟"陆续的在乎感"；
- OFFLINE last_scene 闸：单次离线期最多 1 个 OFFLINE 计划，避免反复骚扰同一用户。

---

## 知识点八：D0 优先 / D1 偏好的双轨推荐

### 背景/场景

新用户**没有右划历史**，用偏好模型给不出可靠画像 — 必须用 **prior**（用户自身画像）作为偏好来源。老用户有充足右划历史，用 **30 天右划画像分布**作为偏好更精准。

### 问题分析

如果冷启动用"用户自身画像"，成熟用户用"右划画像分布"，需要 **两条召回路径** 而不是一套公式。

### 解决方案

D0 走"用户自身画像"prior + **字典序硬排序**（4 级），D1 走"右划画像分布"+ **打分公式**。

```
用户拉 GetTodayFeed
   │
   ├─ 昨天有右划行为?
   │    ├─ 是 → 期望：D1 已离线生成，LPOP 即可
   │    └─ 否 → ColdStartService.buildAndPush 走 D0 实时召回
   │
   └─ D1 已存在 / 队列空 → 同上走 D0 实时
```

### 实现细节

#### D1 打分（4.2.3）

```java
// Ranker.scoreBh
public ScoredCandidate scoreBh(BhCandidate c, PreferenceProfile pref, Map<Long,Integer> mutual) {
    double prefSim  = preferenceSimilarity(c.age, c.race, c.beauty, pref);  // 0.45 权重
    double beauty   = normalize(c.beautyScore);                              // 0.30
    double dist     = distanceDecay(c.distanceKm);                           // 0.15
    double activity = activityScore(c.lastActiveAtMs);                        // 0.10

    double base = 0.45*prefSim + 0.30*beauty + 0.15*dist + 0.10*activity;

    double mutualBonus = mutual.containsKey(c.userId) ? +0.20 : 0;  // 加性 bonus
    double newBhBonus  = isNewBh(c) ? +0.20 : 0;                  // 加性 bonus

    return new ScoredCandidate(c.userId, BH, base + mutualBonus + newBhBonus);
}

// preferenceSimilarity：age 高斯 × beauty 高斯 × race 占比（三项乘积）
private double preferenceSimilarity(int age, String race, int beauty, PreferenceProfile pref) {
    double ageZ    = abs((age - pref.ageMean) / max(0.5, pref.ageStd));
    double ageSim  = ageZ > 3 ? 0 : exp(-0.5 * ageZ * ageZ);

    double beautyZ = abs((beauty - pref.beautyMean) / max(0.5, pref.beautyStd));
    double beautySim = beautyZ > 3 ? 0 : exp(-0.5 * beautyZ * beautyZ);

    double raceSim = pref.raceDist.getOrDefault(race, 0.2);
    return ageSim * beautySim * raceSim;
}
```

#### D0 字典序（4.1 末尾）

```java
// FeedMerger.sortBhForD0 - 4 级字典序
return bh.stream().sorted((a, b) -> {
    // 1. is_new_bh desc
    int c = Boolean.compare(isNewBh(b), isNewBh(a));
    if (c != 0) return c;
    // 2. same_race_as_user desc
    c = Boolean.compare(sameRace(b, user), sameRace(a, user));
    if (c != 0) return c;
    // 3. abs(age - user.age) asc
    c = Integer.compare(abs(a.age - user.age), abs(b.age - user.age));
    if (c != 0) return c;
    // 4. beauty desc
    return Integer.compare(b.beautyScore, a.beautyScore);
}).limit(queueSize).toList();
```

### 权衡取舍

#### D0 为什么不用打分公式？

D0 用户没有右划画像，`preferenceSim` 全是 prior 退化的常量（age high z、低 beauty std），打出来的分数跟字典序效果差不多，但**字典序更可解释 / 运营可调**。

#### D1 为什么不用 MMR 多样性重排？

- D1 队列容量 240 远大于消费配额 50/80/120，本身就有采样面；
- 打分公式里 `preference_similarity` 用高斯软约束，远方差样本自然回退到 prior；
- 多样性重排本身有性能和实现复杂度。

#### D1 取 top 240 后为什么不做 ε-greedy？

跟 MMR 不做的理由一致 — 240 vs 50~120 的余量本身就是天然探索。

---

## 知识点九：call-service 边界严守（CLAUDE.md 红线落地）

### 背景/场景

match-service 实现时需要：
- 查用户 profile (昵称/头像/年龄)；
- 查订阅档位、扣金币；
- 触发 IM 会话 / 消息；
- 拿 DH / BH 候选。

如果直接连 user-service 的 PG 表、payment 表、im 表，必然会撞项目红线（CLAUDE.md 红线 #2："跨服务直连别人家的库表/Redis/对象桶"）。

### 解决方案

**match-service 自己有一套完整 gRPC client + 本地服务自有 PG 库 + 独立 schema**。

| 资源 | match-service 自己的实现 |
|------|-------------------------|
| user 资料查询 | `UserServiceClient.batchGetProfile` gRPC |
| DH / BH 候选 | `UserServiceClient.listDhCandidates / nearbyUsers` gRPC（user-service 内部 PG） |
| 订阅档位 | `PaymentServiceClient.getSubscription` gRPC + 5min cache |
| 扣金币 | `PaymentServiceClient.consumeCoins(idempotent_key, reason)` gRPC |
| 建 IM 会话 | `ImServiceClient.ensureConversation(uid_a, uid_b)` gRPC |
| 发送系统消息 | `ImServiceClient.sendSystemMessage(to, content, type)` gRPC |
| DH 开场白 | `ImServiceClient.triggerDhOpening(dh_id, target_id)` gRPC |
| 在线/离线状态 | `ImServiceClient.listOnlineUsers / listRecentOfflineUsers` gRPC |

### 关键代码细节

```java
// UserServiceClient.batchGetProfile - 失败降级返回空
public List<UserProfileProto> batchGetProfile(List<Long> userIds) {
    if (userIds.isEmpty()) return Collections.emptyList();
    try {
        BatchGetProfilesResponse resp = userServiceBlockingStub.batchGetProfile(req);
        return resp.getProfilesList();
    } catch (Exception e) {
        log.warn("user-service.batchGetProfile failed, count={}", userIds.size(), e);
        return Collections.emptyList();   // ← 失败降级
    }
}

// PaymentServiceClient.consumeCoins - 用 idempotent_key 防重
public ConsumeResult consumeCoins(long userId, int amount, String idempotentKey, String description) {
    ConsumeCoinsRequest req = ConsumeCoinsRequest.newBuilder()
        .setUserId(userId)
        .setAmount(amount)
        .setIdempotentKey(idempotentKey)  // ← 跨进程重试场景关键
        ...
        .build();
}
```

### 实现细节

- **失败降级**：RPC 失败时返回空集合/默认值，调用方处理降级语义（而不是向上抛 — 跨服务 RPC 抖动不应该阻塞主流程）。
- **幂等键**：Super Hi 用金币时传 `"super_hi:<userId>:<targetId>"` 作为 `idempotent_key`，payment-service 端用这个去重。
- **不缓存 user profile**（CLAUDE.md 红线衍生约束）：user-service 自己有缓存，match-service 不维护二级 cache 避免不一致。

### 权衡取舍

#### 为什么 RPC 失败时降级返回空而不是抛错？

- user-service 抖动不应该让 match 主流程挂掉；
- 取不到 profile 时 GetTodayFeed 仍然能返回卡片（profile 字段为空），降低用户感知；
- 重要副作用都走 outbox 兜底了，RPC 失败不是关键路径。

#### 为什么不用 Spring Cloud OpenFeign？

CLAUDE.md 红线 #3："服务间用 HTTP/Feign/RestTemplate 代替 gRPC"。所有跨服务调用**必须** gRPC。

---

## 知识点十：match.source 用 String 而非 Java enum

### 背景/场景

`match` 表的 `source` 字段需要：
1. 当前只有 `SWIPE_MATCH` / `SWIPE_SUPER_HI`；
2. 未来要加 **TBD**（"推荐位 / 活动入口 / 系统配对" 等新入口）；
3. 不想每次新增都跑一次 PG migration。

### 解决方案

```java
// 使用 String 常量（不是 enum）
public static final String SWIPE_MATCH    = "SWIPE_MATCH";
public static final String SWIPE_SUPER_HI = "SWIPE_SUPER_HI";
// TBD 后续添加
```

PRD 7.2 表注释明确说明：`source 枚举(入口动作维度,与 BH/DH 正交)` —— 后续新入口再加 enum，代码用 String 不用 Java enum 避免改一次跑一次 migration。

### 权衡取舍

- **为什么不直接用 Java enum？** 加一个值就要改代码 + 跑 migration → 上线节奏重。
- **优点**：运营/其他微服务扩展时不需要核心服务发版。
- **缺点**：编译期校验不到非法值 — 所以常量集中在 `MatchSourceConst` 集中管理，配合 code review。

---

## 知识点十一：性别反推（异性恋假设）的实现

### 背景/场景

Dating app 平台全平台**异性恋假设** — 不存 `gender_seeking` 字段，服务端按 `oppositeGender(user.gender)` 反推目标性别。

### 解决方案

```java
// CandidateRecaller.oppositeGender
public static int oppositeGender(int gender) {
    if (gender == Gender.GENDER_MALE.getNumber()) return Gender.GENDER_FEMALE.getNumber();
    if (gender == Gender.GENDER_FEMALE.getNumber()) return Gender.GENDER_MALE.getNumber();
    return Gender.GENDER_UNKNOWN.getNumber();
}
```

### 权衡取舍

- **为什么不存 `gender_seeking` 字段？** PRD 已确认 "全平台异性恋假设"，不存 → 简化存储 / 不需要支持非异性恋人群的匹配（产品决策）。
- **LGBT 兼容性如何？** 不支持 — 是产品决策而非技术决策。
- **未指定性别？** 返回 `GENDER_UNKNOWN`，对应的 user 就拿不到任何候选（ColdStartService 走 happy path，userType 检查会失败）。

---

## 知识点十二：BH/DH 双用户体系（产品视角一致 + 内部埋点）

### 背景/场景

平台有 BH（真人）和 DH（数字人）两类用户：
- DH 不打开 App（不接收互动）；
- DH 用来填充新 / 冷门 BH 用户的推荐池 + 通过 DH 计划模拟 like/visit；
- App 视角下两类用户**对外展示一致**（昵称、年龄、照片都一样），不区分。

### 解决方案

#### 数据模型

`like_record.from_user_type` 和 `visit_record.from_user_type` 区分 BH/DH，但下发到 App 时**不发**这两个字段：

```protobuf
message LikeVO {
    int64 from_user_id = 1;          // liker
    string nickname = 2;
    int32 age = 3;
    repeated string photo_keys = 4;
    int64 liked_at_unix_ms = 5;
    string like_content = 6;
    // 不下发 from_user_type / source —— 对外屏蔽 BH/DH 差异
}
```

#### 业务路径

| 动作 | BH | DH |
|------|-----|-----|
| 真人 RIGHT_SWIPE | UPSERT like_record(source=SWIPE_RIGHT) | （DH 不接 swipe — DH 不打开 App） |
| DH 模拟 like | （DH 不主动生成） | UPSERT like_record(source=DH_PLAN_ONLINE/OFFLINE) |
| match 触发的 like | 互划即时 match → 不落 like_record | DH 延迟 match → 不落 like_record |
| SUPER_HI | 不落 like_record（立即 match） | 不落 like_record（立即 match） |

### 权衡取舍

#### 为什么 match.source 不携带 BH/DH 信息？

PRD 7.2 注释明确：`BH/DH 维度需要时由 user_id 反查 user-service.user_type`。

- **理由**：source 与 BH/DH 正交，前者是入口动作维度，后者是用户类型维度。混在一起会让后续统计混乱。
- **代价**：查询时要 JOIN user-service（或 join user 表）才能知道"这是 BH 还是 DH"。

#### 为什么 App 不下发 from_user_type？

产品需求 — 用户视角统一"某某 like 了你"。埋点只在运营/分析平台使用。

---

## 附录：A/B Test 数据期望

D1 cron 上线后，运营根据以下指标判断是否调权重：

| 指标 | 健康预期 |
|------|---------|
| BH→RIGHT→match 转化率 | 5%~10%（双方都 RIGHT 的比例） |
| D0 → D1 切换率 | 新用户 30%+ 在注册第 2 天有划卡行为 |
| BH 池不足告警 | `match.d1.bh_short_ratio` < 30% |
| DH 计划 like → swipe 触发率 | 5%~15%（被关注感带来划卡行为） |
| 重复 match ERROR 日志 | 0/天（理想）/ 偶尔 1~2 条（召回过滤偶发 bug） |

如果指标不健康，调 Nacos：
- BH 池不足 → 提高 `match.d1.bh.radius_km` 或降低 `match.d1.bh_ratio`；
- BH→match 转化率低 → 调 `match.score.*` 权重或加 `mutual_like_bonus` 比例；
- DH 计划触发率低 → 提高 `match.dh_plan.like_content_templates` 文案数量 / 调 visit_ratio。
