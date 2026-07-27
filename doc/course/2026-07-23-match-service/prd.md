# match-service 功能设计文档（类PRD）

> 配套：[`README.md`](./README.md)（学习入口）、[`knowledge.md`](./knowledge.md)（技术原理）、[`interview-qa.md`](./interview-qa.md)（面试问答）
> 参考：`doc/specs/match-service-prd-tech.md`（完整 PRDTech）

## 模块概述

- **业务定位**：dating app 首页"卡片右划即喜欢"的全链路中枢，负责 **卡片队列生成 → 划卡动作处理 → 匹配触发 → 副作用编排**。
- **核心价值**：
  - 让真人（BH）用户在每张卡都能找到匹配概率最高的目标；
  - 通过 BH/DH 双用户体系（数字人填充）解决新用户冷启动与"缺乏互动"的留存问题；
  - 通过 Outbox 兜底机制保证匹配副作用（建 IM 会话 / 系统消息）不因跨服务抖动丢失。
- **用户角色**：
  - **BH（Biological Human）**：真人用户，全部主流程的使用者；
  - **DH（Digital Human）**：数字人，AI 后台驱动，仅作为 BH 用户的"被喜欢 / 被访问"对象出现；
  - 在 App 视角两种用户统一展示，仅 `source` 字段携带差异埋点。

## 上下游依赖

```
            ┌─ user-service (查 profile / 颜值分 / 人种 / 位置)
[m-gw] ─────┤                gRPC
            │   ┌──────────────────────────────────────────┐
            ▼   ▼                                          ▼
        match-service ◄────► payment-service (查订阅档位 / 扣金币)
            │
            ├─► im-service (匹配成功后建会话 + 系统消息 + 触发 DH 开场白)
            ├─► PG (match 自有库)
            └─► Redis (putao:match:* 前缀)
                                                       D1 cron @Scheduled
```

**红线**：match-service 不直连 user 表 / payment 表 / im 表，仅走 gRPC。

---

## 功能清单

| 功能编号 | 功能名称 | 描述 | 优先级 |
|---------|---------|------|--------|
| F001 | **GetTodayFeed** | 拉取当日 feed 卡片（默认 5 张/次） | P0 |
| F002 | **Swipe（LEFT/RIGHT）** | 普通划卡，写历史 + 扣配额 + 触发 match | P0 |
| F003 | **SuperHi** | 付费跳过条件硬匹配，订阅赠送或金币购买 | P0 |
| F004 | **ListMatches** | 我的匹配列表（分页） | P0 |
| F005 | **GetQuota** | 当前每日配额快照 | P0 |
| F006 | **D0 冷启动实时召回** | 新用户首登 / 队列空 → 实时双池召回 + RPUSH | P0 |
| F007 | **D1 日更离线生成** | 美东 03:00 (UTC 07:00) cron 全量重写 D1 | P0 |
| F008 | **ListLikesOfMe** | "谁喜欢了我"列表（屏蔽 BH/DH 来源） | P0 |
| F009 | **ListVisitsOfMe** | "谁访问了我"列表（累加 visit_count） | P0 |
| F010 | **RecordVisit** | 主页访问异步落库（自访问短路） | P1 |
| F011 | **DH ONLINE 计划** | 在线 BH 每 1 分钟 drip 一批 DH 互动 | P1 |
| F012 | **DH OFFLINE 计划** | 离线 ≥ 20 min BH 每 20 分钟收一波 DH 互动 | P1 |
| F013 | **LikeVisitor Executor** | 每 1 分钟扫描到期 `dh_interaction_task` UPSERT + 硬删 | P1 |
| F014 | **MatchOutboxRetry** | 每 30 秒重试 PENDING 的 `match_outbox` 副作用 | P0 |

---

## 功能详情

### F001：GetTodayFeed（首页拉卡片）

#### 1. 功能描述

移动端进入首页时调用，按订阅档位的"当日可划卡"配额拉取一批待显示卡片。

#### 2. 业务规则

- 默认每次拉 5 张（`match.feed.default_count`），上限 20（`match.feed.max_count`）。
- 当日"可划卡片"配额耗尽 → 返回 `exhausted=true, cards=[]`，前端展示"今天已经看完啦"。
- 单次配额 = `min(count, card_limit - cards_used)`。

#### 3. 用户交互流程

```
1. 用户打开首页
2. App 调 GetTodayFeed(count=5)
3. match-service 鉴权 → 配额检查
4. Redis LPOP "putao:match:feed:<user_id>" 5 个元素
5. 用 "putao:match:swiped:<user_id>" SET 做 SMISMEMBER 二次过滤
6. 命中的卡片丢弃，while 循环继续 LPOP 凑足 5 张
7. 拼装 Card VO（nickname / age / photo_keys / distance）
8. 返回给 App
```

#### 4. 数据流转

```
user SWIPE → Redis HASH putao:match:swiped:<user_id>   ← swiped 二次过滤缓存
              └─ LPOP "putao:match:feed:<user_id>"      ← D0/ColdStart 实时写或 D1 覆盖写
                                                              └─ 拼装 VO 调 user-service.batchGetProfile
```

#### 5. 接口设计（移动端）

**HTTP**：POST `/match/feed` Body `{count: 5}`
**底层 gRPC**：`MatchService.GetTodayFeed(GetTodayFeedReq{user_id, count})`
**返回**：
```json
{
  "cards": [
    {
      "target_user_id": 1024,
      "target_user_type": 1,
      "nickname": "Alice",
      "age": 26,
      "photo_keys": ["user/1024/202604/uuid.jpg"],
      "bio": "Hi there",
      "distance_km": 3.2
    }
  ],
  "exhausted": false
}
```

#### 6. 关键实现细节

```java
// FeedService.getTodayFeed 简化
while (result.size() < need && safetyRounds < 8) {
    List<String> batch = redis.opsForList().leftPop(feedKey, batchSize);
    if (batch == null) {
        coldStartService.buildAndPush(userId);   // 队列空 → 实时重建
        batch = redis.opsForList().leftPop(feedKey, batchSize);
    }
    // SMISMEMBER 二次过滤
    List<Boolean> hit = redis.opsForSet().isMember(swipedKey, targetIds);
    result.addAll(batch 中没命中 swiped 的元素);
}
// 拼装 CardVO（调 user-service.batchGetProfile）
```

#### 7. 边界情况

- **队列耗尽**：LPOP 返回空 → 触发 `ColdStartService.buildAndPush` 实时重建 → 再 LPOP；重建还空则 `exhausted=true`。
- **LPOP 出的卡片已被 swipe**：Redis SET SMISMEMBER 命中 → 直接丢弃，继续 LPOP 凑数。
- **配额刚好用完**：返回 `exhausted=true`，cards 空。

---

### F002：Swipe（LEFT / RIGHT 普通划卡）

#### 1. 功能描述

用户对某张卡片做"不喜欢"或"喜欢"动作。RIGHT 命中特定条件（B 互划或 DH）会触发 match。

#### 2. 业务规则

| 动作 | 消耗 | 触发 |
|------|------|------|
| LEFT | 1 张卡 | 仅写历史，不 match |
| RIGHT | 1 张卡 + 1 次右划 | 视对方类型触发即时/延迟 match 或写 like_record |

#### 3. 用户交互流程

```
1. 用户右划卡片（target_user_id）
2. App 调 Swipe RPC
3. Redisson 锁 lock:match:swipe:<user>:<target> 串行化
4. 幂等检查（同 user/target 已存在 swipe → 返回上次结果）
5. 配额扣减（left → cards +1；right → right_swipe +1 & cards +1）
6. 写 user_swipe_history 同事务
7. RIGHT 分支：
   ├─ target = BH：反查 target 是否已 RIGHT/SUPER_HI 过 user
   │   ├─ 是 → 即时 match
   │   └─ 否 → UPSERT like_record
   └─ target = DH → 15s ~ 2min 延迟 match（scheduleDelayedMatch）
8. SADD putao:match:swiped:<user> targetId
```

#### 4. 数据流转

```
Swipe RPC
  ├─ Redisson lock
  ├─ QuotaService.consumeRightSwipe → Redis HASH quota K/V HINCRBY
  ├─ @Transactional
  │   ├─ user_swipe_history INSERT
  │   ├─ (RIGHT + BH 互划) match INSERT IGNORE
  │   │   ├─ like_record 双向 DELETE
  │   │   └─ match_outbox INSERT × 4 (EnsureConversation / 2 SystemMessage / DhOpening)
  │   ├─ (RIGHT + BH 单向) like_record UPSERT
  │   └─ (RIGHT + DH) scheduleDelayedMatch(...) → 内存 TaskScheduler
  └─ markSwiped → Redis SET SADD
```

#### 5. 接口设计

**gRPC**：`MatchService.Swipe(SwipeReq{user_id, target_user_id, direction})`
**返回**：`SwipeResp{match_id}` — `match_id > 0` 即匹配成功；LEFT 或单向 RIGHT / DH 延迟都返回 0。

#### 6. 关键实现细节

```java
// SwipeService.swipe
public SwipeRespVO swipe(long userId, long targetUserId, int direction) {
    RLock lock = redisson.getLock("lock:match:swipe:" + userId + ":" + targetUserId);
    if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
        throw new MatchBizException(CONCURRENT_SWIPE);
    }
    try {
        // 1. 幂等检查
        if (swipeHistoryManager.findByPair(userId, targetUserId) != null) {
            return 上次结果;
        }
        // 2. 配额扣减
        if (direction == LEFT) quotaService.consumeCardOnly(...);
        else quotaService.consumeRightSwipe(...);
        // 3. 写历史 + 触发 match
        return writeHistoryAndTrigger(...);
    } finally {
        lock.unlock();
    }
}
```

#### 7. 边界情况

| 场景 | 处理 |
|------|------|
| 自访问 user==target | `MatchBizException(SELF_OPERATION)` |
| 已 swipe 过同 target | 幂等，返回上次 matchId（不重复扣配额） |
| target 已注销 | 写历史，跳过 match 触发 |
| 配额超限 | HINCRBY -1 回滚 + `QUOTA_*_EXCEEDED` 错误 |

---

### F003：SuperHi（付费跳过条件硬匹配）

#### 1. 功能描述

无论对方是否曾喜欢过我，立即创建 match 并通知对方"X 用 Super Hi 喜欢了你"。

#### 2. 业务规则

- 消耗：1 张卡 + 1 次右划 +（1 次订阅赠送 / 100 金币）。
- 周度（WEEKLY）档**不**送 Super Hi；月度/年度每日赠送 1 次。
- 用完后仍可花 **100 金币** 购买（走 payment-service 扣金币）。
- Super Hi 对 DH **立即**匹配（不延迟，用户付费就要立即看到反馈）。

#### 3. 接口设计

**gRPC**：`MatchService.SuperHi(SuperHiReq{user_id, target_user_id})`
**返回**：`SuperHiResp{match_id, coins_used}` — `coins_used=0` 表示走了订阅赠送。

#### 4. 关键实现细节

```java
// SuperHiService.doSuperHi
int tier = paymentServiceClient.getSubscriptionTier(userId);
int giftLimit = SubscriptionTierConst.dailySuperHiLimit(tier);  // 0 / 0 / 1 / 1

// 配额扣减：先扣 cards + right_swipe，再扣 super_hi 赠送配额
QuotaService.SuperHiCharge charge = quotaService.consumeSuperHi(userId, tier, giftLimit, 100);

// 超赠送则走金币
if (charge.needCoinCharge()) {
    PaymentServiceClient.ConsumeResult res = paymentServiceClient.consumeCoins(
        userId, 100, "super_hi:" + userId + ":" + targetUserId, "SUPER_HI");
    if (!res.ok()) {
        quotaService.rollbackSuperHi(userId);  // 三字段一起回滚
        throw new MatchBizException(SUPER_HI_INSUFFICIENT);
    }
}

// 写历史 + 立即 match
return writeHistoryAndTrigger(userId, targetUserId, targetType);
```

#### 5. 边界情况

- 订阅赠送用完 + 金币不足 → `SUPER_HI_INSUFFICIENT`，已扣的 cards/right_swipe 配额回滚。
- target 已注销 → 抛 `TARGET_USER_NOT_FOUND`。

---

### F004 ~ F005：Match 列表 + 配额快照

这两个接口都是 **纯查询 + 拼装**，3 个 RPC 之外最简单：

#### ListMatches
- 入参：`user_id, page_size, page_token`（page_size ≤ 50）。
- 查询：`SELECT * FROM match WHERE (low = ? OR high = ?) ORDER BY matched_at DESC LIMIT N OFFSET M`。
- 拼装：批量 `user-service.batchGetProfile` 拿 partner 昵称/头像/年龄。

#### GetQuota
- 入参：`user_id`。
- 读取 Redis HASH `putao:match:quota:<user_id>:<yyyymmdd>` 三个字段（`right_swipe` / `cards` / `super_hi`），拼上限值返回。

---

### F006：D0 冷启动实时召回

#### 1. 功能描述

新用户首登 / `GetTodayFeed` 发现 Redis LIST 为空时，**实时**执行双池召回 + 池内排序 + 按比例 merge + RPUSH 写回。

#### 2. 与 D1 的核心差异

| 维度 | D0（实时） | D1（离线） |
|------|------------|-----------|
| 触发 | 每次拉 feed 实时执行 | 美东 03:00 cron 全量 |
| 偏好来源 | 用户自身画像（prior） | 用户 30 天右划画像分布 |
| 池内排序 | 字典序硬排序（4.2.4 已删除打分） | 打分公式（0.45/0.30/0.15/0.10） |
| 池子上限 | DH 240（强制凑满）+ BH ≤ 240 | DH 240 + BH ≤ 240 |

#### 3. 双池召回规则

**DH 池（强制凑满 240，支持渐进扩范围 L0~L3）**：

| Level | age 范围 | beauty 范围 | race |
|-------|----------|-------------|------|
| L0 | 用户 ±5 | 用户 ±15 | 同人种 |
| L1 | 用户 ±5 | 用户 ±15 | 不限 |
| L2 | 用户 ±10 | 用户 ±25 | 不限 |
| L3 | 不限 | 不限 | 不限 |

**BH 池（严格条件一次，不到就不够，merge 阶段 DH 补齐）**：
- 异性 + 同人种 + 年龄 ±5 + 颜值 ±15 + 距离 ≤ 100 km + 7 天活跃。
- BH 条件**不放宽** — 因为放宽会引入低质量真人体验。

#### 4. 池内排序（字典序）

**BH 池 4 级**：is_new_bh desc → same_race desc → abs(age差) asc → beauty desc
**DH 池 3 级**：same_race desc → abs(age差) asc → beauty desc

#### 5. 按比例 merge（默认 bh_ratio = 0.20）

```
target_bh = round(240 × 0.20) = 48
actual_bh = min(48, BH池实际数量)   # 严格不够就不够
short = 48 - actual_bh
actual_dh = (240 - 48) + short = DH 补齐缺口

result = interleave(BH池前actual_bh, DH池前actual_dh)
```

#### 6. 接口与触发点

`ColdStartService.buildAndPush(userId)` 返回写入 Redis LIST 的卡片数。
**触发点**：
1. `GetTodayFeed` LPOP 返回空时（消费路径）。
2. 新用户首登时（其他业务模块可视情况调用）。

---

### F007：D1 日更离线队列（cron）

#### 1. 功能描述

每日凌晨对"昨天有划卡行为"的全量用户，重新计算 D1 偏好画像 → 双池召回 → 池内打分 → 按比例 merge → **DEL + RPUSH 覆盖** Redis LIST。

#### 2. 调度

```java
@Scheduled(cron = "0 0 7 * * *", zone = "UTC")   // 每日美东 EDT 02:00 / EST 03:00
public void runDailyQueueGen() { ... }
```

**时区约定**：cron 用 UTC 表达式，DST 期间实际美东 02:00 而非 03:00 — **trade-off**：用固定 UTC 避免冬令时切换日的双跑 / 漏跑。

#### 3. 防多实例重复执行

```java
String lockKey = "lock:match:d1:" + yyyymmdd;
RLock lock = redisson.getLock(lockKey);
if (!lock.tryLock(0, 60 * 60, TimeUnit.SECONDS)) {
    log.info("D1 already running on another instance, skip");
    return;
}
```

#### 4. 数据流转（单用户）

```
d1UserMapper.listUsersWithSwipeInRange(yesterday)  // 分页 500
  → for each user:
      D1Generator.generateForUser(uid)
        1. 昨天有 swipe？否 → skip
        2. batchGetProfile(uid)
        3. preferenceBuilder.build(uid)   ← 样本 < 10 回退用户 prior
        4. listDhCandidates / nearbyUsers 召回
        5. Ranker 打分（base + mutual + new_bh bonus）
        6. 取 top 240
        7. FeedMerger.merge(bhRatio)
        8. DEL key + RPUSH 240
```

#### 5. 池内打分公式（Ranker.java）

```
S(c) = base_score(c) + mutual_like_bonus(c) + new_bh_bonus(c)

base = 0.45 * preference_sim         ← age/beauty 高斯 × race 占比
     + 0.30 * normalize(beauty)
     + 0.15 * distance_decay         ← exp(-d/50km)，DH 固定 0.5
     + 0.10 * activity_score         ← exp(-(now - last_active)天/7)，DH 固定 0.5

mutual_like_bonus  = +0.20 if  c 是 BH 且 target 曾对 user 做过 RIGHT/SUPER_HI
new_bh_bonus       = +0.20 if  c 是 BH 且 (now - c.created_at) ≤ 3天
```

所有权重 / bonus / 窗口均走 `MatchProperties` (Nacos 配置)，运营可调。

#### 6. 失败兜底

- 单用户失败 → 该用户跳过，下一个继续；日志记录。
- 整个 D1 cron 失败 → Redis LIST **不被覆盖**，用户继续消费旧队列。
- 旧队列消费完 → `GetTodayFeed` 触发 `ColdStartService.buildAndPush` 实时 D0 重建（对 App 透明）。

---

### F008 ~ F010：Like / Visit 列表 + 访问上报

#### F008：ListLikesOfMe（"谁喜欢了我"）

- **业务定位**：用户看不到 match 之前的"暗恋"状态集中地。
- **数据源**：`like_record`（`SELECT WHERE to_user_id=? AND deleted=false ORDER BY liked_at DESC`）。
- **来源多样**：
  - 真人 RIGHT_SWIPE 单向（`source = SWIPE_RIGHT`）；
  - DH ONLINE 计划（`source = DH_PLAN_ONLINE`）；
  - DH OFFLINE 计划（`source = DH_PLAN_OFFLINE`）。
- **SUPER_HI / 互划即时 match 路径不落 like_record**（没有"暗恋"窗口），由 `match` 表承载关系。
- **对外屏蔽来源**：`from_user_type` 和 `source` 不下发（产品只展示"某某 like 了你"）。

#### F009：ListVisitsOfMe

- 数据源：`visit_record`（同人多次访问 `visit_count` 累加）。
- 来源：真人 `PROFILE_VIEW` + DH 计划 ONLINE / OFFLINE。

#### F010：RecordVisit

- **异步落库**：`@Async("visitRecordExecutor")` 走独立线程池。
- **自访问短路**：`viewer == target` 直接 return。
- **失败容忍**：catch Exception 仅 WARN，不抛 — 列表少几条不影响产品。

#### 关键清理：match 时删除双向 like_record

```java
// MatchService.createMatch 同事务内
@MatchManager.InsertResult insertResult = matchManager.insertIgnoreConflictWithLog(...);
if (insertResult.success()) {
    // 双向 like_record 升级为 match，原"暗恋"状态失去意义，软删
    likeRecordManager.softDeleteByPair(userA, userB);
}
```

---

### F011 ~ F013：DH 模拟互动计划（三个 scheduler）

> 意图：让新注册 / 长期没真人互动的 BH 用户也有 like/visit 数据可看。

#### F011：OnlinePlanGenerator（每 1 分钟）

- **信号源**：`im-service.ListOnlineUserIds(since, until, 5000)` 读 `im:presence:online` ZSet。
- **过滤三道闸**（缺一不可）：
  1. **类型闸**：to_user_id 必须是 BH；
  2. **cooldown**：`putao:match:dh_plan:cooldown:<user>` 存在则跳（默认 2h）；
  3. **任务表去重**：`dh_interaction_task` 已有同 scene 任务则跳。
- **生成**：调 `user-service.listDhCandidates` 拉 `rand(5,10)` 个 DH，按 60%/40% 分配 VISIT/LIKE。
- **execute_time 均匀随机分布**在 `[now, now + 30min]` —— 关键：不能密集。
- **收尾**：批量 INSERT 任务 + 写 cooldown + 写 `last_scene=ONLINE`。

#### F012：OfflinePlanGenerator（每 20 分钟）

- **信号源**：`im-service.ListRecentOfflineUsers(since, until, 5000)` 读 PG `user_online_session`。
- **过滤三道闸**（无 cooldown）：
  1. lastScene 闸（"OFFLINE" 跳过 — 单次离线期最多 1 个 OFFLINE 计划）；
  2. 任务表去重；
  3. 类型闸。
- **生成**：拉 `rand(3,6)` 个 DH（比 ONLINE 少 — 离线一段时间收一波不要夸张）。
- **收尾**：写 `last_scene=OFFLINE`（**不**写 cooldown）。

#### F013：LikeVisitorTaskExecutor（每 1 分钟）

- **扫描**：`SELECT * FROM dh_interaction_task WHERE execute_time <= now() ORDER BY execute_time LIMIT 1000`。
- **执行**（每条独立短事务）：
  - LIKE → `likeRecordManager.upsert(from=DH, to=BH, like_content=...)`；
  - VISIT → `visitRecordManager.upsert(from=DH, to=BH)`（累加 visit_count）。
- **硬删任务行**（不软删）：`DELETE FROM dh_interaction_task WHERE id=?`。
- **失败保留**：UPSERT 失败 → 行不删，下一轮重试。

#### 真实感约束（防穿帮，全部 Nacos 可调）

| 约束 | 默认值 | Nacos key |
|------|--------|-----------|
| ONLINE 单次生成 DH 数 | 5~10 | `match.dh_plan.online_count_range = [5,10]` |
| OFFLINE 单次生成 DH 数 | 3~6 | `match.dh_plan.offline_count_range = [3,6]` |
| ONLINE cooldown | 2h | `match.dh_plan.online_cooldown_seconds = 7200` |
| OFFLINE 离线阈值 | 20 min | `match.dh_plan.offline_threshold_seconds = 1200` |
| OFFLINE 最大回看 | 3h | `match.dh_plan.offline_lookback_seconds = 10800` |
| ONLINE execute_window | 30 min | `match.dh_plan.online_execute_window_min = 30` |
| OFFLINE execute_window | 30 min | `match.dh_plan.offline_execute_window_min = 30` |
| VISIT/LIKE 比例 | 60%/40% | `match.dh_plan.visit_ratio = 0.6` |
| 24h DH like 上限 | 15 | `match.dh_plan.daily_dh_like_cap = 15` |
| 24h DH visit 上限 | 25 | `match.dh_plan.daily_dh_visit_cap = 25` |
| like_content 文案池 | JSON 列表 | `match.dh_plan.like_content_templates` |

---

### F014：MatchOutboxRetry（每 30 秒）

#### 1. 功能描述

`MatchService.createMatch` 在本地事务内已写入 `match` 表 + 清理了 `like_record`，但跨服务副作用（IM 建会话 / 系统消息 / DH 开场白）必须容错。失败的副作用记录在 `match_outbox`，后台每 30 秒扫描重试。

#### 2. 三类副作用

```
ENSURE_CONVERSATION(uid_a, uid_b) → im-service.EnsureConversation（幂等建会话）
SYSTEM_MSG(to, content)            → im-service.SendSystemMessage （双方各一条）
DH_OPENING                         → im-service.TriggerDhOpening  （DH 主动开场白）
```

#### 3. 调度与重试

```java
@Scheduled(fixedDelay = 30_000L)
public void run() {
    int delivered = outboxService.deliver();
}

private void scheduleRetry(MatchOutboxEntity task) {
    int attempts = task.getAttempts();
    long nextDelaySec = Math.min(60 * 30, Math.pow(2, attempts + 1) * 5);   // 指数退避，封顶 30 min
    Instant nextRetry = Instant.now().plus(Duration.ofSeconds(nextDelaySec));
    outboxManager.markRetry(task.getId(), nextRetry, max_attempts);
}
```

- 成功 → `status = DONE`；
- 失败 → `attempts++`, `next_retry_at = now + 2^(attempts+1) * 5s`（指数退避 5s → 10s → 20s → ...，封顶 30 min）；
- 达到 `max_attempts` → `status = DEAD`。

#### 4. 跨服务失败不阻塞 UX

match-service 的核心 promise：**用户视角 swipe / SuperHi 接口不会因为 IM 抖动失败**。一旦 outbox 入库，迟早会跑成功。

---

## 数据模型

### PG Schema（match 自有 schema）

```sql
-- 1. 划卡历史（高写入，UNIQUE 约束兜底幂等）
CREATE TABLE user_swipe_history (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    target_user_type SMALLINT NOT NULL,        -- 1=BH, 2=DH
    direction SMALLINT NOT NULL,               -- 1=LEFT, 2=RIGHT, 3=SUPER_HI
    swiped_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, target_user_id)
);

-- 2. 匹配关系（主键保证 (a,b) 与 (b,a) 视为同一）
CREATE TABLE match (
    id BIGINT PRIMARY KEY,
    user_id_low BIGINT NOT NULL,               -- min(uid1, uid2)
    user_id_high BIGINT NOT NULL,              -- max(uid1, uid2)
    matched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source VARCHAR(30) NOT NULL,               -- SWIPE_MATCH / SWIPE_SUPER_HI
    UNIQUE (user_id_low, user_id_high)
);

-- 3. 副作用 retry
CREATE TABLE match_outbox (
    id BIGINT PRIMARY KEY,
    match_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,               -- ENSURE_CONVERSATION/SYSTEM_MSG/DH_OPENING
    payload_json JSONB NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL                -- PENDING/DONE/DEAD
);

-- 4. Like 记录（"单向未回应的喜欢"）
CREATE TABLE like_record (
    id BIGINT PRIMARY KEY,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    from_user_type SMALLINT NOT NULL,          -- 1=BH 2=DH
    source SMALLINT NOT NULL,                  -- 1=SWIPE_RIGHT 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    like_content VARCHAR(200),                 -- DH 任务携带
    liked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_user_id, to_user_id)
);

-- 5. Visit 记录（同人多次 UPSERT 累加 visit_count）
CREATE TABLE visit_record (
    id BIGINT PRIMARY KEY,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    from_user_type SMALLINT NOT NULL,
    source SMALLINT NOT NULL,                  -- 1=PROFILE_VIEW 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    visit_count INT NOT NULL DEFAULT 1,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_user_id, to_user_id)
);

-- 6. DH 模拟任务（短生命周期，Executor 执行后硬删）
CREATE TABLE dh_interaction_task (
    id BIGINT PRIMARY KEY,
    from_user_id BIGINT NOT NULL,              -- DH
    to_user_id BIGINT NOT NULL,                -- BH
    action SMALLINT NOT NULL,                  -- 1=LIKE 2=VISIT
    scene SMALLINT NOT NULL,                   -- 1=ONLINE 2=OFFLINE
    execute_time TIMESTAMPTZ NOT NULL,
    like_content VARCHAR(200)
);
```

### Redis 数据结构（全部 `putao:match:` 前缀）

| Key | 类型 | TTL | 用途 |
|-----|------|-----|------|
| `putao:match:quota:<user>:<yyyymmdd>` | HASH | 36h | right_swipe / cards / super_hi 字段 HINCRBY |
| `putao:match:feed:<user>` | LIST | 7d | "uid:type" 元素 LPOP 消费 / RPUSH 写入 |
| `putao:match:swiped:<user>` | SET | 永久 | 已 swipe 的 target 二次过滤缓存 |
| `putao:match:pref:<user>` | HASH | 24h | 偏好画像缓存 |
| `putao:match:dh_plan:cursor:online` | STRING | 永久 | OnlinePlanGenerator 游标（epoch ms） |
| `putao:match:dh_plan:cursor:offline` | STRING | 永久 | OfflinePlanGenerator 游标 |
| `putao:match:dh_plan:cooldown:<user>` | STRING | 2h | ONLINE cooldown 闸 |
| `putao:match:dh_plan:last_scene:<user>` | STRING | 永久 | "ONLINE"/"OFFLINE" |
| `lock:match:d1:<yyyymmdd>` | Redisson | 1h | D1 cron 防重 |
| `lock:match:swipe:<user>:<target>` | Redisson | 5s | 单次 swipe 串行化 |
| `lock:match:dh_plan:online_sweep` | Redisson | 60s | ONLINE 多实例分布式锁 |
| `lock:match:dh_plan:offline_sweep` | Redisson | 30min | OFFLINE 多实例分布式锁 |
| `lock:match:dh_plan:executor` | Redisson | 60s | Executor 多实例分布式锁 |

---

## 状态机 / 关键流转

### 一次 swipe 命中 BH 互划的完整状态变化

```
用户 A：getTodayFeed → 看到 B → swipe RIGHT
  ├─ Redisson lock
  ├─ swipeHistoryManager.findByPair(A,B) → null（首次）
  ├─ quotaService.consumeRightSwipe(A) → HINCRBY cards +1, right +1
  ├─ @Transactional 开始
  │   ├─ user_swipe_history INSERT(A,B,RIGHT)
  │   ├─ swipeHistoryManager.findByPair(B,A) → 存在且 direction=RIGHT → 互划
  │   ├─ matchMapper.insertIgnore(low(A,B), high, SWIPE_MATCH)
  │   ├─ likeRecordManager.softDeleteByPair(A,B)  ← 双向清理
  │   └─ match_outbox INSERT × 4（EnsureConv / 2 SystemMsg / DhOpening）
  ├─ markSwiped(A) → SET SADD B
  └─ 返回 matchId

后台（每 30s）：
  MatchOutboxRetry → 扫描 PENDING 任务 → 执行 IM 副作用 → 成功 DONE / 失败 next_retry_at
```

### 一次 RIGHT DH 的延迟匹配完整状态

```
用户 A：swipe RIGHT (target=DH)
  ├─ Redisson lock
  ├─ @Transactional
  │   ├─ user_swipe_history INSERT(A,DH,RIGHT)
  │   └─ scheduleDelayedMatch(A, DH)  ← 注意：这里只 schedule，不真创建 match
  └─ markSwiped(A) → SADD DH

内存 15s~2min 后：
  DhDelayedMatchService scheduler 回调
  ├─ getUserType(DH) → 校验仍是 DH
  └─ matchService.createMatch(A, DH, SWIPE_MATCH)  ← 这里才落 match
      ├─ match INSERT IGNORE
      ├─ like_record DELETE 双向（A↔DH 可能之前有 LIKE 来源）
      └─ outbox INSERT × 4
```

---

## 监控指标

| 指标 | 类型 | 采集 |
|------|------|------|
| `match.feed.cards_returned` | histogram | GetTodayFeed |
| `match.swipe.duration_p99` | gauge | SwipeService |
| `match.swipe.idempotent.count` | counter | 幂等返回路径 |
| `match.quota.exceeded.{right_swipe\|cards\|super_hi}` | counter | 配额抛错 |
| `match.d1.queue_size.{generated_users\|cards}` | counter | D1 cron 完成 |
| `match.outbox.{pending\|delivered\|retry\|dead}` | gauge / counter | outbox 任务状态 |
| `match.dh_plan.cursor.online.age_min` | gauge | 监控游标老化 |
| `match.dh_plan.task.backlog` | gauge | `execute_time <= now - 5min` 的行数 |
| `match.dh_plan.like_record.24h` | counter | BH 24h DH like 数 |
| `match.outbox.action.{action}.duration` | timer | 单次 IM 副作用耗时 |

---

## 边界与异常（速查表）

| 场景 | 处理 |
|------|------|
| 用户拉 feed 时配额耗尽 | `exhausted=true, cards=[]` |
| Super Hi 金币不足且无订阅赠送 | `SUPER_HI_INSUFFICIENT`（已扣配额回滚） |
| 重复 swipe 同一 target | 幂等返回上次 matchId |
| 划卡 target 已注销 | 写历史，跳过 match 触发 |
| D1 生成时该用户已注销 | 单用户失败跳过 |
| DH 延迟回调时 user/dh 已注销 | 校验失败丢日志，不抛 |
| 同一对用户重复触发 match | `match` UNIQUE 兜底 → ERROR 日志 + 返回 existing |
| 队列耗尽但配额还有 | LPOP 空 → ColdStartService 实时重建 |
| RecordVisit 自访问 | controller 短路 ok |
| RecordVisit 异步写入失败 | WARN 日志，不阻塞调用方 |
| DH 计划 generator cursor 长期不动 | 告警 + 重置 cursor |
| DH 池为空 | 该用户 skip，不报错 |
| Executor 任务持续积压 | `task_backlog` 监控打 WARN |
| DH 24h 上限达成 | 减少本轮生成数 / 跳过 |
| D1 cron 整批失败 | Redis LIST 不被覆盖，App 透明继续消费旧队列 |
