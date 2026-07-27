# Match 推荐系统 · D0 冷启动 & D1 日更队列 完整学习笔记

> 学习日期：2026-07-24
> 学习范围：`dating-server/match-service` 下 `service/ColdStartService`、`service/D1Generator`、`recommend/CandidateRecaller`、`recommend/FeedMerger`、`recommend/Ranker`、`recommend/PreferenceBuilder`、`recommend/PreferenceProfile`、`scheduler/D1QueueScheduler`、`scheduler/OnlinePlanGenerator`、`scheduler/OfflinePlanGenerator`、`service/FeedService`（消费侧闭环）,相关的 `config/MatchProperties` + `constant/MatchRedisKey`。
>
> 目标：吃透两条核心链路——**D0 冷启动实时重建 feed** 与 **D1 日更个性化 feed**，以及它们在移动端 `getTodayFeed` 消费侧如何协同。

---

## 目录

1. [整体心智模型：为什么是 D0 + D1 双队列？](#1-整体心智模型为什么是-d0--d1-双队列)
2. [5 分钟看懂：一次"滑卡片"在 match-service 内部的完整路径](#2-5-分钟看懂一次滑卡片在-match-service-内部的完整路径)
3. [通览：D0 冷启动 vs D1 日更 差异速查表](#3-通览d0-冷启动-vs-d1-日更-差异速查表)
4. [D0 冷启动链路 详解](#4-d0-冷启动链路-详解)
5. [D1 日更队列链路 详解](#5-d1-日更队列链路-详解)
6. [打分器 Ranker 公式推导](#6-打分器-ranker-公式推导)
7. [FeedMerger：两池按比例交错合并](#7-feedmerger两池按比例交错合并)
8. [Runner 入口：Cron / 在线 / 离线 三种触发](#8-runner-入口cron--在线--离线-三种触发)
9. [消费侧 FeedService：D0/D1 队列如何被读](#9-消费侧-feedserviced0d1-队列如何被读)
10. [Redis 键 / 配置 / 类的全景图](#10-redis-键--配置--类的全景图)
11. [面试高频问题 & 答法（Q&A 思路）](#11-面试高频问题--答法qa-思路)
12. [易踩坑点 & 改进点](#12-易踩坑点--改进点)

---

## 1. 整体心智模型：为什么是 D0 + D1 双队列？

### 1.1 推荐系统最朴素的形态

如果不优化，最直接的做法是：**用户每次请求 feed → 实时调召回 → 实时打分 → 返回结果**。

这种做法在 Tinder 这种体量下会爆：

- **延迟高**：每次请求都要走召回 + 排序 + 拼装，单次 200ms~1s 不奇怪。
- **DB 压力爆炸**：每个用户每次都要做候选查询 + 排序。
- **不可控**：没有"预热"，冷启动时（用户刚注册）压根没历史可参考。

### 1.2 双队列的核心思想：**离线预生成 + 实时消费**

```
┌──────────────────────────────────────────────────────────────────┐
│                  时钟 / 事件 触发                                  │
│   ┌──────────────┐      ┌──────────────────┐                     │
│   │ 用户打开 App │      │ 离线 cron 调度器  │                     │
│   │  → 队列空？  │      │  每天 07:00 UTC   │                     │
│   └──────┬───────┘      └────────┬─────────┘                     │
│          │                       │                                │
│          ▼                       ▼                                │
│   ┌──────────────┐      ┌──────────────────┐                     │
│   │ ColdStart    │      │ D1Generator      │                     │
│   │ Service.D0   │      │.generateForUser  │                     │
│   │ (D0 实时重建) │      │ (D1 日更个性化)  │                     │
│   └──────┬───────┘      └────────┬─────────┘                     │
│          │     RPUSH 240 张      │     DEL + RPUSH 240 张          │
│          ▼                       ▼                                │
│   ┌─────────────────────────────────────────────────────────┐    │
│   │   Redis LIST: putao:match:feed:<uid>                     │    │
│   │   元素格式: "<targetUserId>:<userType>" (BH=1/DH=2)      │    │
│   └─────────────────────────────────────────────────────────┘    │
│                          ▲                                        │
│                          │ LPOP (消费)                             │
│   ┌──────────────────────┴───────────────────────┐               │
│   │   FeedService.getTodayFeed()                 │               │
│   │   移动端拉卡片的主入口                         │               │
│   └──────────────────────────────────────────────┘               │
└──────────────────────────────────────────────────────────────────┘
```

- **D0 冷启动队列（实时触发）**：用户注册后第一次打开 / 队列被消费空后**实时**生成。**没有偏好**可参考，只能基于"用户自身画像"用保守阈值找候选。
- **D1 日更队列（定时触发）**：每天 07:00 UTC 由 Cron 触发，给"昨天有滑过卡"的用户**预生成**今天一整天的 240 张候选。**基于偏好画像**（从最近 30 天右划数据学习）。

> 一句话总结：**D0 是应急粮（没历史/历史不够用），D1 是预制菜（基于用户口味提前做好）**。

### 1.3 为什么是 240 张？

`match.d1.queue_size: 240` 固定值。这是一个工程权衡：

- **240 ÷ 一天 200 张滑卡上限 ≈ 1.2 天** 的素材量，能覆盖用户晚高峰 + 第二天上午。
- 又不至于太多造成 Redis 内存浪费（每列表项 < 30 字节，240 条 ≈ 7KB）。
- BH 候选池本身也按 240 收口（`POOL_TARGET = 240`）。

---

## 2. 5 分钟看懂：一次"滑卡片"在 match-service 内部的完整路径

当 App 客户端拉下一批卡片（典型 count=5）时，**从 App 视角看**，只发生了一件事：

```
App → gRPC GetTodayFeed(userId, count=5)
        ↓
       FeedService.getTodayFeed(userId, 5)
        ↓
       ┌──────────────────────────────────────────┐
       │ 1. 配额检查 (QuotaService)                 │
       │    - 调 payment-service 拿订阅等级         │
       │    - 看当日配额是否已用完                   │
       │ 2. while 循环 LPOP + 二次过滤:              │
       │    - LPOP feed:<uid> 一次拿 5 张           │
       │    - 过滤掉已 swipe 的 (SMISMEMBER)        │
       │    - 不够 5 张 → 再 LPOP (≤ 8 轮)          │
       │    - 队列空 → 触发 ColdStartService        │
       │      实时重建 240 张 RPUSH                  │
       │    - 仍空 → 兜底返回 exhausted=true         │
       │ 3. 拼装 CardVO:                            │
       │    - 批量拿 profile (UserServiceClient)    │
       │    - 填 nickname / age / bio / avatar       │
       └──────────────────────────────────────────┘
        ↓
       返回 GetTodayFeedRespVO { cards[], exhausted }
```

而**这 240 张卡片在 Redis 里哪里来的**？

```
┌────────────────────────────────────────────────────────────────┐
│  路径 A：D1 日更（推荐）                                        │
│  D1QueueScheduler @07:00 UTC cron                              │
│    → 昨天有 swipe 的用户 (D1UserMapper, 批 500)                │
│    → D1Generator.generateForUser(userId)                       │
│    → 两池召回 + 打分 + merge + DEL+RPUSH                       │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  路径 B：D0 冷启动（兜底）                                      │
│  路径 A 失败 / 队列被消费空 / 新用户                             │
│    → ColdStartService.buildAndPush(userId)                     │
│    → 用户画像中心点 + 渐进召回 + merge + RPUSH                  │
└────────────────────────────────────────────────────────────────┘
```

**两条路径的产出物完全一样**：往 Redis `putao:match:feed:<uid>` LIST 里塞 240 张卡片，元素格式是 `<uid>:<userType>`。

而 FeedService 完全感知不到这些卡片是 D0 还是 D1 的，它**只认 Redis LIST**。

---

## 3. 通览：D0 冷启动 vs D1 日更 差异速查表

| 维度                       | D0 冷启动                                            | D1 日更                                              |
| -------------------------- | ---------------------------------------------------- | ---------------------------------------------------- |
| **触发时机**               | 实时：队列空 / 新用户 / 消费完兜底                   | 离线：每天 07:00 UTC cron                            |
| **入口类**                 | `ColdStartService.buildAndPush(userId)`              | `D1Generator.generateForUser(userId)` + `D1QueueScheduler.runDailyQueueGen()` |
| **生效前提**               | 无（任何用户都能触发）                               | 用户**昨天有过 swipe**（看 swipe_history）            |
| **用户偏好画像**           | 没有，用 D0 字典序硬规则                             | 有，基于最近 30 天右划数据学习                         |
| **BH 池半径**              | 100km（`coldStartBhRadiusKm`）                       | 200km（`d1BhRadiusKm`）                              |
| **BH 池年龄窗**            | `userAge ± 5`                                        | `userAge ± 10`                                       |
| **DH 池召回**              | **渐进** L0→L1→L2→L3（宽→窄）                        | **单层**（基于偏好画像）                              |
| **BH 池打分**              | 无打分（sorted by D0 字典序）                        | 完整 4 项公式 + mutual_like_bonus + new_bh_bonus      |
| **DH 池打分**              | 无打分（sorted by D0 字典序）                        | 完整 4 项公式                                         |
| **BH 比例**                | 20%（`coldStartBhRatio`）                            | 40% 基础 + ±20% 个性化偏移（`d1BhRatio` + `d1PreferenceOffset`） |
| **merge 后入 Redis**       | RPUSH（**追加**）                                    | **DEL + RPUSH**（**覆盖**）                          |
| **典型场景**               | 新用户、刚注册、队列被清空                           | 日常推荐的主通路                                       |

---

## 4. D0 冷启动链路 详解

### 4.1 入口：`ColdStartService.buildAndPush(long userId)`

7 个步骤串成一条线：

```
[1] 取用户画像
       ↓
[2] 计算 exclude list (已 swipe 的 target)
       ↓
[3] DH 池 — 渐进扩范围 L0→L3
       ↓
[4] BH 池 — 严格条件一次拉
       ↓
[5] 池内排序 (D0 字典序)
       ↓
[6] 按比例 merge (BH 20% / DH 80%)
       ↓
[7] RPUSH 写入 Redis LIST
```

#### 步骤 1：取用户画像

```java
List<UserProfileProto> profiles = userServiceClient.batchGetProfile(List.of(userId));
UserProfileProto user = profiles.get(0);
int userGender  = user.getGender().getNumber();
int targetGender = CandidateRecaller.oppositeGender(userGender); // 异性恋假设
int userAge     = user.getAge() > 0 ? user.getAge() : 25;
int userBeauty  = 60; // user.proto 暂无 beauty 字段,默认 60
```

- 通过 `UserServiceClient` gRPC 调 user-service 拿到当前用户的 profile（年龄、性别、位置、人种等）。
- `oppositeGender` 简单硬编码：1 ↔ 2，其它返回 UNKNOWN。
- 缺字段给默认值（age 缺则 25，beauty 缺则 60），是工程上的"宁可给丑默认值也别让后面 NPE"。

#### 步骤 2：exclude list

```java
List<Long> exclude = recaller.excludeUserIds(userId);
```

`excludeUserIds` 内部是查 `user_swipe_history`——这个用户滑过的所有目标用户都排除掉。**避免重复推已滑过的人**。

```java
public List<Long> excludeUserIds(long userId) {
    Set<Long> set = new HashSet<>(swipeHistoryManager.listSwipedTargetIds(userId));
    return new ArrayList<>(set);
}
```

#### 步骤 3+4：两池召回

这是 D0 最有特色的部分——**DH 池渐进扩范围**：

```java
List<DhCandidate> dhPool = recaller.recallDhPoolD0(userId, targetGender, exclude, userAge, userBeauty, user.getPreferredLocation());
```

`recallDhPoolD0` 内部是 4 层循环 L0→L1→L2→L3，每层不到 240 就放宽阈值：

```java
case "L0" -> { ageWindow=5;  beautyWindow=15; races=同人种 }  // 最严格
case "L1" -> { ageWindow=5;  beautyWindow=15; races=不限 }   // 放开人种
case "L2" -> { ageWindow=10; beautyWindow=25; races=不限 }   // 放宽年龄颜值
case "L3" -> { ageWindow=60; beautyWindow=60; races=不限 }   // 兜底：要多少给多少
```

**设计含义**：先尝试"应该比较匹配的"（同龄、同人种、颜值接近），不够就放宽。这种"渐进扩范围"在召回阶段比"硬阈值"更鲁棒——避免"匹配阈值卡太严直接召回 0 条"。

```java
List<BhCandidate> bhRaw = recaller.recallBhPool(
        userId, 100, // 100km
        ageMin = max(18, userAge - 5),
        ageMax = userAge + 5,
        beautyMin = max(0, 60 - 15),
        beautyMax = 60 + 15,
        lastActiveWithinDays = 7,
        exclude);
```

BH 池**只用一次请求**，条件严格（直径 100km + 7 天活跃 + 年龄 ±5 + 颜值 ±15）。**不够就不够，由 DH 池补齐**。

#### 步骤 5：池内排序（D0 字典序）

**不引入打分器**，仅用硬规则排序：

DH 排序键（3 级）：
1. 同人种：desc
2. `|age - userAge|`：asc
3. beautyScore：desc

BH 排序键（4 级）：
1. 是否新 BH（new_bh_window_days = 3 天内注册）：desc
2. 同人种：desc
3. `|age - userAge|`：asc
4. beautyScore：desc

```java
List<BhCandidate> bhSorted = merger.sortBhForD0(bhRaw, isNewBhPredicate, userRace, userAge, 3);
List<DhCandidate> dhSorted = merger.sortDhForD0(dhPool, userRace, userAge);
```

#### 步骤 6：按比例 merge

```java
MergedFeed merged = merger.merge(bhSorted, dhSorted, computeBhRatioD0(), props.getD1QueueSize());
```

`computeBhRatioD0()` 返回 `0.20`（固定 20% BH）。

merge 后的 240 张构成一个 LIST 元素数组：`[{uid:123, type:1}, {uid:456, type:2}, ...]`，BH 和 DH 交错排列。

#### 步骤 7：RPUSH

```java
String key = MatchRedisKey.feed(userId); // putao:match:feed:<uid>
List<String> elements = new ArrayList<>();
for (MergedEntry e : merged.entries()) {
    if (!seen.add(e.candidateId())) continue; // 去重
    elements.add(props.formatFeedElement(e.candidateId(), e.userType()));
    // 元素格式: "<uid>:<userType>" e.g. "12345:1"
}
stringRedisTemplate.opsForList().rightPushAll(key, elements);
stringRedisTemplate.expire(key, Duration.ofSeconds(props.getFeedTtlSeconds())); // 7 天
```

注意：是 `rightPushAll` 即 RPUSH，把所有元素推到 LIST 右边。

> **⚠️ 关键设计点**：D0 是 RPUSH **追加**而不是 DEL+RPUSH。如果 D0 在 D1 队列非空时被触发（例如某个用户被手动触发冷启动），会**叠加**而非替换。**这是一个有意的设计**——D0 是兜底/补偿，覆盖 D1 反而会浪费 D1 的偏好努力。
> 而 D1 是 **DEL + RPUSH** 覆盖，因为 D1 是个性化的"今天最好的"，必须用最新结果覆盖。

### 4.2 D0 时间线总结

```
用户打开 App → getTodayFeed → FeedService 检测到队列空
   ↓
ColdStartService.buildAndPush(userId)
   ↓
约 100~300ms 内（2 ~4 层 RPC + merge + Redis 写）
   ↓
240 张卡片可消费
```

---

## 5. D1 日更队列链路 详解

D1 的链路更复杂，涉及**用户偏好建模**和**完整打分**。

### 5.1 入口：D1QueueScheduler.runDailyQueueGen()

```java
@Scheduled(cron = "0 0 7 * * *", zone = "UTC")
public void runDailyQueueGen() {
    // 1. 分布式锁 (按 yyyymmdd 分锁)
    String date = now-1day.formatted("yyyyMMdd");
    RLock lock = redissonClient.getLock(MatchRedisKey.lockD1(date)); // lock:match:d1:20260723
    lock.tryLock(0, 60*60, TimeUnit.SECONDS);

    // 2. 拉"昨天有 swipe"的用户 (批 500)
    List<Long> userIds = d1UserMapper.listUsersWithSwipeInRange(yesterdayStart, yesterdayEnd, 500);
    for (Long userId : userIds) {
        d1Generator.generateForUser(userId);
    }
}
```

**3 个关键设计**：

1. **cron 0 0 7 * * * UTC** —— 调度器按 UTC 跑（与项目统一时区规则一致），对应美东 02:00/03:00。
2. **Redisson 分布式锁** —— 多实例部署时只跑一份；锁 key 含日期，避免跨天冲突。
3. **批 500** —— 避免一次拉太多用户导致 gRPC 拥塞。

### 5.2 主流程：D1Generator.generateForUser(userId)

```java
[1] 前置条件: 昨天有 swipe
       ↓
[2] 拉用户画像
       ↓
[3] 构建偏好画像 (PreferenceProfile)
       ↓
[4] exclude = 已 swipe + 自身
       ↓
[5] DH 池召回 (用偏好画像)
       ↓
[6] BH 池召回 (严格条件)
       ↓
[7] 双池打分 (Ranker)
       ↓
[8] 取 top 240
       ↓
[9] 转回 Candidate 对象
       ↓
[10] merge (BH 40% / DH 60% + 个性化偏移)
       ↓
[11] DEL + RPUSH 覆盖 Redis
```

#### 步骤 1：前置条件

```java
if (!swipeHistoryManager.existsSwipeInRange(userId, yesterdayStart, yesterdayEnd)) {
    return 0; // 没 swipe 过就不生成,留给 D0 兜底
}
```

**为什么必须有 swipe？** D1 是"个性化"队列，没历史就别浪费算力——直接 D0 兜底。

#### 步骤 3：构建偏好画像

```java
PreferenceProfile pref = preferenceBuilder.build(userId);
if (!pref.isValid()) {
    pref = buildFallbackPrior(user); // 样本不足(<10)时回退到用户自身画像
}
```

`PreferenceProfile` 记录用户的**右划偏好分布**：

```java
Double ageMean;     // 右划 target 平均年龄
Double ageStd;      // 右划 target 年龄标准差
Double beautyMean;  // 右划 target 平均颜值
Double beautyStd;   // 右划 target 颜值标准差
Map<String, Double> raceDist; // 人种分布
Double dhBhRatio;   // 30 天右划 DH / (DH + BH)
int sampleCount;    // 样本数
```

`isValid()` 的定义：样本 > 0 且四个均值/标准差都不为 null。

样本不足时回退用用户自身画像（centered on user himself）：

```java
pref.setAgeMean(user.age);
pref.setAgeStd(8.0);
pref.setBeautyMean(60.0);
pref.setBeautyStd(15.0);
pref.setSampleCount(0);
```

#### 步骤 5：DH 池召回（D1）

```java
List<DhCandidate> dhRaw = recaller.recallDhPoolD1(userId, targetGender, exclude, pref);
```

`recallDhPoolD1` **单层**完成（不像 D0 渐进），而是用偏好画像算 mean±2σ 作为窗口：

```java
int ageMin = pref.ageMean - 2 * max(1, pref.ageStd);
int ageMax = pref.ageMean + 2 * max(1, pref.ageStd);
int beautyMin = pref.beautyMean - 20;
int beautyMax = pref.beautyMean + 20;
```

> **2σ 原则**：约 95% 的"理想对象"会落在 ageMean ± 2σ 窗口内。这是高斯分布的 95% 置信区间。

#### 步骤 6：BH 池召回

```java
List<BhCandidate> bhRaw = recaller.recallBhPool(
        userId, 200, // 200km
        ageMin = max(18, userAge - 10),
        ageMax = userAge + 10,
        beautyMin = 0, beautyMax = 100,  // 颜值不卡(BH 已不规范)
        lastActiveWithinDays = 7,
        exclude);
```

与 D0 区别：半径 200km（更大）、年龄窗 ±10（更宽）、颜值不卡（0~100）。

#### 步骤 7：双池打分

```java
for (DhCandidate c : dhRaw) {
    dhScored.add(ranker.scoreDh(c, pref));
}
for (BhCandidate c : bhRaw) {
    bhScored.add(ranker.scoreBh(c, pref, mutual));
}
```

`mutual` 是反查表——"候选里哪些曾对 user 做过 RIGHT/SUPER_HI"。当前实现是空 map（简化）,见 Ranker 部分。

#### 步骤 8+9：top 240 + 反查

```java
List<Long> dhTopIds = ranker.topIds(dhScored, props.getD1QueueSize()); // 240
List<Long> bhTopIds = ranker.topIds(bhScored, props.getD1QueueSize());

// 反查回 Candidate 对象
for (Long id : dhTopIds) for (DhCandidate c : dhRaw) if (c.userId == id) { dhFinal.add(c); break; }
```

`topIds` 把 ScoredCandidate 按分数降序取前 240 个的 id。然后用 id 反查回原始 Candidate 对象（因为排序后 ScoredCandidate 只丢了 userId 和 score，原始 profile 字段在 dhRaw/bhRaw 里）。

#### 步骤 10：merge

```java
MergedFeed merged = merger.merge(bhFinal, dhFinal, computeBhRatioD1(pref), 240);
```

`computeBhRatioD1(pref)` 是 D1 的**个性化比例**：

```java
double base = 0.40; // d1BhRatio
if (pref.isValid && pref.dhBhRatio != null && sampleCount >= 10) {
    double offset = (0.5 - pref.dhBhRatio) * 0.20 * 2;
    offset = clamp(offset, -0.20, +0.20);
    return clamp(base + offset, 0, 1);
}
```

**含义**：

- 用户右划中 DH 占比 = 0.5 → 偏中性 → offset = 0 → bhRatio = 0.40
- 用户右划中 DH 占比 = 0.8 → 偏好 DH → offset = (0.5-0.8)*0.4 = -0.12 → bhRatio = 0.28（少给 BH）
- 用户右划中 DH 占比 = 0.2 → 偏好 BH → offset = +0.12 → bhRatio = 0.52（多给 BH）

逻辑是 **"用户右划偏好 DH，则减少 BH 比例"**。

#### 步骤 11：DEL + RPUSH 覆盖

```java
String key = MatchRedisKey.feed(userId);
stringRedisTemplate.delete(key);      // 先删
pushToRedis(userId, merged.entries()); // 再 RPUSH
```

**与 D0 的关键区别**：D1 是覆盖（DEL + RPUSH），D0 是追加（RPUSH）。

---

## 6. 打分器 Ranker 公式推导

### 6.1 完整公式

Ranker 的 Javadoc 写得很清楚：

```
S(c) = base_score(c) + mutual_like_bonus(c) + new_bh_bonus(c)
```

其中 BH 和 DH 的 base_score 权重略有不同：

```
BH:  base = 0.45 * preference_sim + 0.30 * normalize(beauty) + 0.15 * distance_decay + 0.10 * activity
DH:  base = 0.45 * preference_sim + 0.30 * normalize(beauty) + 0.15 * 0.5           + 0.10 * 0.5
          (DH 距离+活跃度固定 0.5,因为 DH 没有"附近"概念)
```

`BH_Weights = [0.45, 0.30, 0.15, 0.10]`
`DH_Weights = [0.45, 0.30, 0.15, 0.10]`

四项依次是：**偏好相似度、颜值、距离、活跃度**。权重和 = 1.0。

### 6.2 preference_similarity：三层高斯 × 概率

```java
double preferenceSimilarity(int age, String race, int beauty, PreferenceProfile pref) {
    // 1. age 高斯
    double ageZ = Math.abs((age - pref.ageMean) / Math.max(0.5, pref.ageStd));
    double ageSim = ageZ >= 3 ? 0.0 : Math.exp(-0.5 * ageZ * ageZ);

    // 2. beauty 高斯
    double beautyZ = Math.abs((beauty - pref.beautyMean) / Math.max(0.5, pref.beautyStd));
    double beautySim = beautyZ >= 3 ? 0.0 : Math.exp(-0.5 * beautyZ * beautyZ);

    // 3. race 概率
    double raceSim = 0.5;
    if (race != null && pref.raceDist != null) {
        Double r = pref.raceDist.get(race);
        raceSim = r != null ? r : 0.2;
    }

    return ageSim * beautySim * raceSim;
}
```

**含义**：

- **age 高斯**：`ageSim = exp(-0.5 * z²)`，z = (candidate.age - pref.ageMean) / pref.ageStd。这是经典的高斯 PDF 形式（去掉常数项后的简化），z=0 时 1.0，z=1 时 0.61，z=2 时 0.13，z=3 时 0.011。
- **beauty 高斯**：同理。
- **race 概率**：直接拿 history 里"这个种族被右划的比例"，最高 1.0（只右划过这个种族），没匹配给 0.2（兜底）。
- **三者相乘**：三个独立维度都"像"才得分高。

> **Z_CUTOFF = 3.0**：高斯在 |z|≥3 时概率 < 0.001，相当于"完全不匹配"。

### 6.3 距离衰减：`distanceDecay(distanceKm)`

```java
private double distanceDecay(double distanceKm) {
    if (distanceKm < 0) return 0.5; // DH 用
    return Math.exp(-distanceKm / 50.0);
}
```

**含义**：距离每 50km 衰减到 e^(-1) ≈ 0.368。100km 时 0.135，200km 时 0.018。

> 50km 是"半衰期"——超过 50km 距离贡献跌到 0.5 以下。

DH 距离固定 0.5，是因为 DH 是算法自动生成的"虚拟用户"，没有真实位置。

### 6.4 活跃度：`activityScore(lastActiveMs)`

```java
private double activityScore(long lastActiveAtMs) {
    if (lastActiveAtMs <= 0) return 0.0;
    double days = Duration.between(last, Instant.now()).toDays();
    return Math.exp(-days / 7.0);
}
```

**7 天半衰期**——最后活跃 7 天前衰减到 0.368，14 天前 0.135，30 天前 ≈ 0.013。

### 6.5 mutual_like_bonus

```java
double mutualBonus = 0.0;
if (mutualSwipedMe != null && mutualSwipedMe.containsKey(c.getUserId())) {
    mutualBonus = props.getMutualLikeBonus(); // 默认 +0.20
}
```

**含义**：如果候选曾对 user 做过 RIGHT/SUPER_HI，加 0.20 分（因为"互相喜欢"的概率大幅提高）。

> 当前代码里 `mutualSwipedMe` 始终是空 map（`buildMutualMap` 留空），所以**这个奖励实际未生效**。源码注释里也写了"实际生产建议加一个 mapper.findByTargetsAndDirection(targetIds, dir)"。

### 6.6 new_bh_bonus

```java
if (isNewBh(c.getCreatedAtMs(), props.getNewBhWindowDays())) {  // 3 天内注册
    newBhBonus = props.getNewBhBonus(); // 默认 +0.20
}
```

**含义**：新注册 BH（3 天内）奖励 +0.20——给新用户流量曝光。

### 6.7 取 top N

```java
public List<Long> topIds(List<ScoredCandidate> scored, int n) {
    return scored.stream()
        .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
        .limit(n)
        .map(ScoredCandidate::candidateId)
        .collect(Collectors.toList());
}
```

简单 `sorted + limit`，O(N log N) 排序。生产中如果 N 很大可以用堆优化，但 240 这个量级排序无所谓。

`topN` 还有个 O(N²) 的反查版本（保留给 caller），生产中应该重构成"先 map<id, candidate> 再查找"。

---

## 7. FeedMerger：两池按比例交错合并

### 7.1 merge 核心算法

```java
public MergedFeed merge(List<?> bhPool, List<DhCandidate> dhPool, double bhRatio, int queueSize) {
    int targetBh = (int) Math.round(queueSize * bhRatio);  // 目标 BH 数
    int actualBh = Math.min(targetBh, bhPool.size());       // 实际能拿到的 BH
    int shortBh = targetBh - actualBh;                      // BH 缺口
    int actualDh = (queueSize - targetBh) + shortBh;        // DH 补齐缺口
    actualDh = Math.min(actualDh, dhPool.size());           // 也不超过 DH 池

    // 交错插入:每 step 张 DH 塞 1 张 BH
    int step = bhRatio <= 0 ? 1 : (int) Math.max(1, Math.round(1.0 / bhRatio));
    int cycle = step + 1;
    
    while (merged.size() < queueSize && (bhCount < actualBh || dhCount < actualDh)) {
        for (int i = 0; i < step && bhCount < actualBh && merged.size() < queueSize; i++) {
            merged.add(MergedEntry.fromBh(bhPool.get(bhCount)));
            bhCount++;
        }
        if (dhCount < actualDh && merged.size() < queueSize) {
            merged.add(MergedEntry.fromDh(dhPool.get(dhCount)));
            dhCount++;
        }
    }
    return new MergedFeed(merged, usedDh, actualBh, actualDh);
}
```

### 7.2 比例举例

假设 queueSize = 240, bhRatio = 0.20（D0）：
- targetBh = 48
- 实际 BH 池假设只拉回来 20（靠近的不够）
- actualBh = 20, shortBh = 28
- actualDh = 240 - 48 + 28 = 220

merge 后的 list 长度 = 240，BH 占 20 个，DH 占 220 个。**BH 不足时 DH 自动补齐**。

假设 queueSize = 240, bhRatio = 0.40（D1），且样本足：
- targetBh = 96
- 假设 BH 池足: actualBh = 96, shortBh = 0
- actualDh = 240 - 96 = 144

### 7.3 交错逻辑

```java
int step = bhRatio <= 0 ? 1 : (int) Math.max(1, Math.round(1.0 / bhRatio));
```

step = 1/bhRatio，所以：
- bhRatio = 0.20 → step = 5 → 每 5 张 DH 塞 1 张 BH
- bhRatio = 0.40 → step = 2.5 → round=3 → 每 3 张 DH 塞 1 张 BH
- bhRatio = 0.50 → step = 2 → 每 2 张 DH 塞 1 张 BH

但实际 if 实现是"先加 1 张 BH，再加 step 张 DH"——比例约是 1 : step。B 图代码实际是 `for (i<step; i++) add BH; then add DH`。**注释写的 1.5:1 和实际代码不完全一致**——这是个潜在的 bug 区域。

---

## 8. Runner 入口：Cron / 在线 / 离线 三种触发

match-service 有 3 个 Scheduler 周期性触发不同链路：

### 8.1 D1QueueScheduler —— 每日 07:00 UTC

```java
@Scheduled(cron = "0 0 7 * * *", zone = "UTC")
public void runDailyQueueGen() { ... }
```

- 触发 D1Generator.generateForUser
- 批 500 用户循环
- Redisson 锁 `lock:match:d1:<yyyymmdd>` 保证多实例唯一

### 8.2 OnlinePlanGenerator —— 每 1 分钟

```java
@Scheduled(fixedDelay = 60_000L) // 1 min
public void run() {
    // 扫在线用户(从 im-service 拿)
    // 生成 DH 互动任务(VISIT/LIKE)
}
```

调 `DhInteractionPlanService.runOnlinePlan()` → 拿最近 30 分钟在线用户 → 给每个生成 5~10 条 VISIT/LIKE 任务。

**与 D0/D1 的关系**：OnlinePlan 是给 BH 用户**注入 DH 流量**的——让 BH 的"喜欢我的人"列表（VISIT/LIKE 通知）里有内容可看。它**不影响 feed 队列**。

### 8.3 OfflinePlanGenerator —— 每 20 分钟

```java
@Scheduled(fixedDelay = 1_200_000L) // 20 min
public void run() {
    // 扫最近离线用户(20min~3h)
    // 生成 DH 互动任务
}
```

类似 OnlinePlan，但目标用户是"最近下了线"的用户。

> Online/Offline 这两个 Scheduler 跟 D0/D1 **没有直接关系**，它们是 DH 互动任务（DH → BH 的虚拟 like/visit）的生产者。**容易面试混淆，要分清**。

---

## 9. 消费侧 FeedService：D0/D1 队列如何被读

### 9.1 getTodayFeed 完整流程

```java
public GetTodayFeedRespVO getTodayFeed(long userId, int count) {
    // 1. 配额检查:订阅等级 + 当日已用
    if (quotaService.isCardsExhausted(userId, tier)) return exhausted;
    int need = min(count, dailyRemaining);

    // 2. while 循环拉卡片 (≤8 轮)
    while (result.size() < need && safetyRounds < 8) {
        // 2a. LPOP 一次
        List<String> batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
        if (batch == null) {
            // 2b. 队列空 → 触发 ColdStartService.buildAndPush
            coldStartService.buildAndPush(userId);
            batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
            if (batch == null) break; // 兜底
        }
        // 2c. 二次过滤:SMISMEMBER 过滤已 swipe
        List<Boolean> hits = isMember(swipedKey, targetIds);
        for (int i = 0; i < batch.size(); i++) {
            if (hits.get(i)) continue; // 已 swipe 跳过
            result.add(new CardVO(targetId, type));
        }
    }

    // 3. 拼装 CardVO:批量拿 profile,填 nickname/age/bio/avatar
    List<UserProfileProto> profiles = userServiceClient.batchGetProfile(ids);
    for (CardVO card : result) {
        UserProfileProto p = byId.get(card.targetUserId);
        card.setNickname(p.getNickname());
        card.setAge(p.getAge());
        card.setBio(p.getBio());
        card.setPhotoKeys(List.of(p.getAvatar().getOriginalKey()));
    }

    return new GetTodayFeedRespVO(result, result.isEmpty());
}
```

### 9.2 关键设计点

#### 9.2.1 二次过滤：Redis SET

```java
String swipedKey = MatchRedisKey.swiped(userId); // putao:match:swiped:<uid>
Boolean hit = stringRedisTemplate.opsForSet().isMember(swipedKey, tid);
```

用户每次 swipe 成功时调 `FeedService.markSwiped(userId, targetId)`：

```java
public void markSwiped(long userId, long targetUserId) {
    stringRedisTemplate.opsForSet().add(MatchRedisKey.swiped(userId), String.valueOf(targetUserId));
}
```

**为什么需要二次过滤？**——简单说就是 **D1/D0 队列生成时不可能精确到 ms 级避免"已 swipe target"**：

- 用户的 swipe 行为是实时的
- D1 是昨天 07:00 生成的——今早用户又滑了 50 张，这 50 个 id 已经 SADD 到 swiped SET
- 但 D1 队列里如果还有这 50 个 id（因为 D1 不查 swiped SET），需要消费时过滤

#### 9.2.2 安全轮次 ≤ 8

```java
while (result.size() < need && safetyRounds < 8)
```

**为什么是 8？** 240 张队列 + 8 轮 LPOP = 1920 张，最多过滤后剩下 240 张。**防止死循环**——理论上 swiped SET 命中率 100% 时队列永远不够。

#### 9.2.3 队列空触发 ColdStart

```java
if (batch == null) {
    coldStartService.buildAndPush(userId);
    batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
    if (batch == null) break;
}
```

**这是 D0 的触发路径**——用户消费完队列后，下次拉卡片时就**实时生成新队列**。

> 设计上很关键：**D0 不是只在注册时用，更是消费空后的兜底**。

#### 9.2.4 批量 RPC 拼装 profile

```java
List<UserProfileProto> profiles = userServiceClient.batchGetProfile(ids);
```

`batchGetProfile` 一次 gRPC 拿到所有 target 的 profile，避免 N 次 RPC。**这是性能优化的关键点**——如果不批量，5 张卡 = 5 次 RPC。

---

## 10. Redis 键 / 配置 / 类的全景图

### 10.1 Redis 键一览

| Key 格式                          | 类型 | 用途                            | TTL          |
| --------------------------------- | ---- | ------------------------------- | ------------ |
| `putao:match:feed:<uid>`          | LIST | D0/D1 推荐队列                 | 7 天         |
| `putao:match:swiped:<uid>`        | SET  | 用户已 swipe 的 target 集合     | 跟随业务     |
| `putao:match:quota:<uid>:<date>`  | HASH | 当日配额(右划/卡片/超赞)         | 36h          |
| `putao:match:pref:<uid>`          | HASH | 偏好画像缓存                    | 24h          |
| `lock:match:d1:<yyyymmdd>`        | LOCK | D1 cron 分布式锁                | 1h           |
| `lock:match:swipe:<a>:<b>`        | LOCK | swipe 串行化锁                  | 短           |
| `lock:match:pair:<low>:<high>`    | LOCK | match 串行化锁                  | 短           |
| `putao:match:dh_plan:cursor:online` | STR | Online 游标                     | 永久         |
| `putao:match:dh_plan:cursor:offline`| STR | Offline 游标                    | 永久         |
| `putao:match:dh_plan:cooldown:<uid>` | STR | ONLINE cooldown                 | 2h           |
| `putao:match:dh_plan:last_scene:<uid>` | STR | 最后 scene (ONLINE/OFFLINE)    | 永久         |

> 命名规范严格遵守 `putao:<service>:<domain>:<id>`（红线之一）。

### 10.2 关键配置项

`MatchProperties.java` 里所有 match 相关配置（Nacos 注入）。重点：

```yaml
# D0 冷启动
match.cold_start.bh_ratio: 0.20
match.cold_start.bh.radius_km: 100
match.cold_start.bh.age_window: 5
match.cold_start.bh.beauty_window: 15
match.cold_start.bh.last_active_within_days: 7
match.cold_start.levels.dh: L0:L1:L2:L3

# D1
match.d1.bh_ratio: 0.40
match.d1.bh.radius_km: 200
match.d1.bh.last_active_within_days: 7
match.d1.preference_enabled: true
match.d1.preference_offset: 0.20
match.d1.queue_size: 240

# 打分权重
match.score.bh_weights: 0.45,0.30,0.15,0.10  # pref, beauty, dist, activity
match.score.dh_weights: 0.45,0.30,0.15,0.10
match.score.mutual_like_bonus: 0.20
match.score.new_bh_bonus: 0.20
match.score.new_bh_window_days: 3
match.score.min_samples: 10

# DH 计划
match.dh_plan.online_count_min: 5
match.dh_plan.online_count_max: 10
match.dh_plan.offline_count_min: 3
match.dh_plan.offline_count_max: 6
match.dh_plan.online_cooldown_seconds: 7200
match.dh_plan.daily_dh_like_cap: 15
match.dh_plan.daily_dh_visit_cap: 25
match.dh_plan.visit_ratio: 0.6

# Redis TTL
match.redis.feed_ttl_seconds: 604800  # 7 天
match.redis.quota_ttl_seconds: 129600 # 36h
match.redis.pref_ttl_seconds: 86400   # 24h
```

### 10.3 核心类协作图

```
┌────────────────────────────────────────────────────────────────────┐
│  Controller / gRPC                                                  │
│      ↓                                                              │
│  FeedService (消费)  ←────  ColdStartService (D0) ─────┐            │
│      ↓                      ↓                          │            │
│  QuotaService                D1Generator (D1)         │            │
│      ↓                       ↓                        │            │
│  StringRedisTemplate ──────── recommend/*              │            │
│      ↑                       ↓                        │            │
│  Redis LIST                CandidateRecaller ───┐     │            │
│  putao:match:feed:<uid>    FeedMerger           │     │            │
│                            Ranker               │     │            │
│                            PreferenceBuilder    │     │            │
│                            PreferenceProfile    │     │            │
│                              ↓                  │     │            │
│                            UserSwipeHistoryManager UserServiceClient
│                              (查 swipe_history)     (gRPC)         │
└────────────────────────────────────────────────────────────────────┘
```

---

## 11. 面试高频问题 & 答法（Q&A 思路）

### Q1：为什么 match-service 用 D0 + D1 双队列，而不是每次实时召回？

**答法（STAR）**：

- **Situation**：Tinder-like 的匹配场景，每个用户每天可能刷 200 张卡片。
- **Task**：要保证低延迟（<200ms）+ 高质量 + 不能把 DB 打爆。
- **Action**：采用 **离线预生成 + 实时消费** 模式。
  - D1 cron 每天 07:00 给活跃用户**预生成 240 张**到 Redis LIST。
  - App 拉卡片时直接 **LPOP**，O(1) 取数。
  - 队列空了再**实时**走 D0 兜底。
- **Result**：单次拉卡片延迟 < 50ms，DB 召回压力下降到 1/100。

> **答法要点**：先讲业务问题，再讲 Pre-Generation 模式的优势，最后讲 trade-off（实时性 vs 算力）。

### Q2：D0 为什么要渐进扩范围（L0→L3）？

**答法**：

- **问题**：如果第一层 L0 就给定了很严的阈值（同龄 + 同人种 + 颜值 ± 15），**热门用户可能池里就 0 个人**，冷启动直接失败。
- **方案**：分 4 层逐步放宽，每层不到 240 就去下一层。
- **收益**：L0 拿到的是"最匹配"的（保证质量），L3 是兜底（保证数量）。
- **类似场景**：Es 搜索的"渐进式精确度"、LinkedIn 的"先 strong tie 再 weak tie"。

> **答法要点**：体现"分层兜底"思路——先 try 严格，再 try 宽松。

### Q3：D1 的打分公式是怎么设计的？每一项权重为什么是这样？

**答法**：

- **公式**：`S = 0.45*preference_sim + 0.30*beauty + 0.15*distance + 0.10*activity`
- **解释权重**：
  - 0.45 preferences_sim —— 个性化是核心，匹配用户真实审美最重要
  - 0.30 beauty —— 颜值是硬通货，但要排在偏好之后（不能纯看脸）
  - 0.15 distance —— 物理距离是限制条件，但不致命
  - 0.10 activity —— 活跃度是"加分项"，不是必要条件
- **公式选择理由**：
  - preferences_sim 用 **age 高斯 × beauty 高斯 × race 概率**——三个独立维度都"像"才得分高
  - distance 用 **指数衰减**（e^(-d/50)）——50km 是个心理舒适距离
  - activity 用 **指数衰减**（e^(-days/7)）——7 天半衰期，30 天前基本归零

### Q4：D1 是 DEL+RPUSH 覆盖，D0 是 RPUSH 追加，为什么设计不同？

**答法**：

- **D1 是"今天的最佳推荐"**——必须用最新偏好计算，覆盖昨天的结果。
- **D0 是"兜底/补偿"**——发生在 D1 之后或 D1 失败时，追加既不浪费 D1 算力，又能补到新候选。
- **如果 D0 也覆盖**：用户首次开 App 时 D0 跑完覆盖了 D1 早 07:00 的结果，**个性化努力白费**。
- **如果 D1 不覆盖**：用户每天 07:00 往队列里塞 240 张，**一周后队列里有 1680 张过期数据**，消费时全是过滤掉的。

### Q5：D1 怎么保证 cron 任务多实例不重复跑？

**答法**：

- **Redisson 分布式锁** `lock:match:d1:<yyyymmdd>`，key 含日期。
- `tryLock(0, 3600, TimeUnit.SECONDS)`：waitTime=0（不等），leaseTime=1h（自动过期防死锁）。
- 锁 key 包含日期可以**防止跨天重复**——比如 07:00 cron 跑了一半跨到 08:00，第二天的新锁不会冲突。

### Q6：消费侧为什么要二次过滤（SMISMEMBER）？

**答法**：

- **D1 队列是早上 07:00 生成的**，今天用户已经 swipe 了 50 张。
- D1 生成时没查 swiped SET（swipe_history 是流式的，可能 D1 生成后才写入）。
- 消费时 LPOP 出来有可能命中已 swipe 的，必须用 Redis SET 二次过滤。
- **本质**：**推荐系统经典 tradeoff**——生成时省 IO（全量扫 swipe_history 太重），消费时多查一次（user 局部查询便宜）。

### Q7：D1 偏好画像样本不足时怎么回退？

**答法**：

- `isValid()` = sampleCount > 0 && ageMean != null && ageStd != null && beautyMean != null && beautyStd != null
- 不满足时调用 `buildFallbackPrior(user)`，用用户自身画像作为 prior：
  - ageMean = user.age, ageStd = 8.0
  - beautyMean = 60, beautyStd = 15
  - sampleCount = 0
- **效果**：相当于"我不知道你喜欢啥，就让你找和你类似的人"——比随机召回更合理。

### Q8：为什么 BH 池 240 条，DH 池也是 240 条？够吗？

**答法**：

- 240 ÷ 一天 200 张滑卡上限 ≈ 1.2 天。
- 每池 240 是 POOL_TARGET 硬编码，超过就截断。
- **不够会怎样**：merge 阶段会自动补齐（BH 不足由 DH 补），不会出现 result < queueSize。
- **太多会怎样**：Redis 内存浪费 + 跨用户推送数据量大。

### Q9：D1 cron 失败怎么办？比如用户没 swipe 历史 / user-service 抖动？

**答法**：

- **用户没 swipe 历史**：`D1Generator.generateForUser` 步骤 1 直接返回 0，**不影响其他人**。
- **user-service 抖动**：单个用户失败被 try-catch 吞掉，**只丢一个用户**。
- **整个 cron 失败**：`Redisson` 锁 1h 自动过期，下次 cron 时重试。
- **冷启动兜底**：D1 没跑成功时用户 App 拉卡片会触发 D0 兜底——**D0 是终极兜底**。

> 答法要点：体现"防御性编程 + 多层兜底"。

### Q10：如果让你设计这个系统，你会怎么改进？

**答法（开放问题）**：

可以提的方向：

1. **打分公式**：现在权重是固定的，可以用 **多臂老虎机 / LinUCB** 在线学习每个用户的权重。
2. **召回源**：现在只用了 user-service 的 listDhCandidates/nearbyUsers，可以加 **协同过滤**（item2vec 训练嵌入向量）、**向量召回**（Milvus）。
3. **冷启动**：现在 D0 字典序硬规则，可以加 **基于规则标签的相似度**（同一标签簇优先）。
4. **实时性**：现在 D1 是一天一次的，可以加 **实时增量更新**——swipe 后立刻调整下次推送（Kafka 异步更新）。
5. **A/B 测试**：打分公式和权重可以加 **bucket 路由** + **指标埋点**，让算法工程师能在线做 A/B。
6. **mutual_like_bonus 没生效**：当前 `buildMutualMap` 是空 map，可以批量反查 swipe_history 加分。

---

## 12. 易踩坑点 & 改进点

### 12.1 代码层的坑

| 编号 | 位置                             | 问题                                                                       | 改进                                                  |
| ---- | -------------------------------- | -------------------------------------------------------------------------- | ----------------------------------------------------- |
| 1    | `D1Generator.buildMutualMap`     | 返回空 map，**mutual_like_bonus 实际未生效**                               | 批量反查 `findByTargetsAndDirection`                  |
| 2    | `FeedMerger.merge`               | 交错逻辑注释写 1.5:1 实际代码不一致                                          | 重写单元测试覆盖所有比例 case                          |
| 3    | `Ranker.topN`                    | O(N²) 反查 (`items.stream().filter`)                                       | 改为 `Map<id, candidate>` 一次构建                    |
| 4    | `PreferenceBuilder.build(long)`  | 占位代码 `ageSum += s.getTargetUserId()`，没注入真实年龄                    | 重构为只保留 `build(long, Map<TargetStats>)`           |
| 5    | `FeedService.getTodayFeed`       | 二次过滤用了 N 次 `isMember`（单条 RPC），应该用 `SMISMEMBER` 批量          | 改用 `stringRedisTemplate.opsForSet().isMember(key, collection)` |
| 6    | `ColdStartService.buildAndPush`  | RPUSH 追加，可能在 D1 队列上叠加                                              | 文档明确"仅在队列空或用户缺画像时调用"                |
| 7    | `D1Generator.generateForUser`    | `_dhFinal` 反查用 N×N 循环                                                   | 改 `Map<id, dh>`                                      |
| 8    | `MatchRedisKey.LOCK_D1` 用 yyyymmdd | 跨天时锁可能过期未释放，新一天拿新锁不会冲突，但 1h leaseTime 可能不够 24h 大数据量跑完 | 自适应 leaseTime，或分片跑              |

### 12.2 架构层的隐患

| 编号 | 隐患                                                                              | 影响                                       |
| ---- | --------------------------------------------------------------------------------- | ------------------------------------------ |
| 1    | D1 cron 跑 100 万用户要 24h+                                                    | 第二天 07:00 又触发，叠加重跑               |
| 2    | 单 Redis 实例承载所有 feed LIST                                                   | 大促时单实例 QPS 上限                       |
| 3    | `user_swipe_history` 没建索引，全表扫描 30 天                                      | D1 召回慢                                  |
| 4    | DH 用户的"位置"在 user-service 怎么算？没有真实位置就用 city 中心                   | distance_decay 固定 0.5 是兜底              |
| 5    | 离线 cron 触发 D1 时，**用户可能正在实时刷卡片**——RPUSH 进来的卡片和消费的在并发      | 需要分布式锁或乐观重试                       |

### 12.3 数据层

| 编号 | 数据表                    | 作用                         | 关键字段                                                |
| ---- | ------------------------- | ---------------------------- | ------------------------------------------------------- |
| 1    | `user_swipe_history`      | 用户滑卡历史                 | user_id, target_user_id, direction, target_user_type, created_at |
| 2    | `dh_interaction_task`     | DH 互动任务表               | from_user_id, to_user_id, action, scene, execute_time   |
| 3    | `d1_user` (自定义 mapper) | D1 cron 查"昨天有 swipe"     | derived from swipe_history                              |

---

## 附录 A：一张总图（记忆用）

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                调度层                                        │
│  ┌──────────────────────────────┐  ┌────────────────────┐  ┌──────────────┐ │
│  │ D1QueueScheduler            │  │ OnlinePlanGenerator│  │OfflinePlan   │ │
│  │ 07:00 UTC cron              │  │ 1 min              │  │20 min        │ │
│  └──────────────┬───────────────┘  └────────┬───────────┘  └──────┬───────┘ │
│                 │                            │                     │         │
│                 ▼                            ▼                     ▼         │
│  ┌──────────────────────────────┐  ┌────────────────────────────────────┐    │
│  │ D1Generator                  │  │ DhInteractionPlanService            │    │
│  │  - PreferenceBuilder          │  │  - ONLINE/OFFLINE 计划              │    │
│  │  - CandidateRecaller (DH/BH) │  │  - 写 dh_interaction_task          │    │
│  │  - Ranker                     │  └────────────────────────────────────┘    │
│  │  - FeedMerger (4:6)           │              │ LikeVisitorTaskExecutor      │
│  └──────────────┬───────────────┘              ▼                              │
│                 │ DEL + RPUSH         ┌────────────────────┐                  │
│                 │                    │ 写 dh_plan_cooldown │                 │
│                 │                    │ 写 dh_plan_last_scene│                 │
│                 │                    └────────────────────┘                  │
└─────────────────┼─────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                              Redis 数据层                                     │
│  putao:match:feed:<uid>              (LIST, 240 张 D0/D1 卡片)                │
│  putao:match:swiped:<uid>            (SET,  已 swipe target)                   │
│  putao:match:quota:<uid>:<date>      (HASH, 当日配额)                          │
│  putao:match:dh_plan:cooldown:<uid>  (STR,  2h 冷却)                           │
│  putao:match:dh_plan:last_scene:<uid>(STR,  ON/OFF 防重)                       │
│  lock:match:d1:<yyyymmdd>            (LOCK, cron 互斥)                         │
└──────────────┬───────────────────────────────────────────────────────────────┘
               │ LPOP
               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                              消费层                                          │
│  FeedService.getTodayFeed(userId, count)                                      │
│    1. quota check                                                              │
│    2. while (LPOP + SMISMEMBER二次过滤):                                       │
│         - 队列空 → ColdStartService.buildAndPush (D0 兜底)                    │
│    3. batchGetProfile (UserServiceClient gRPC)   拼装 CardVO                   │
│  FeedService.markSwiped(userId, targetId)  → SADD swiped SET                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 附录 B：核心代码位置索引

| 文件                                                                                  | 角色                              |
| ------------------------------------------------------------------------------------- | --------------------------------- |
| `service/ColdStartService.java`                                                       | D0 冷启动入口                     |
| `service/D1Generator.java`                                                            | D1 日更生成                       |
| `recommend/CandidateRecaller.java`                                                    | 两池召回器                        |
| `recommend/FeedMerger.java`                                                           | 排序 + merge                      |
| `recommend/Ranker.java`                                                               | D1 打分器                         |
| `recommend/PreferenceBuilder.java`                                                    | 偏好建模器                        |
| `recommend/PreferenceProfile.java`                                                    | 偏好数据载体                      |
| `scheduler/D1QueueScheduler.java`                                                     | D1 cron 入口                      |
| `scheduler/OnlinePlanGenerator.java` / `OfflinePlanGenerator.java`                    | DH 互动计划入口                   |
| `service/FeedService.java`                                                            | feed 消费入口 + 二次过滤          |
| `service/DhInteractionPlanService.java`                                               | DH 互动业务编排                   |
| `service/QuotaService.java`                                                           | 配额检查                          |
| `config/MatchProperties.java`                                                         | Nacos 配置注入                    |
| `constant/MatchRedisKey.java`                                                         | Redis Key 拼装                    |
| `client/UserServiceClient.java` / `client/ImServiceClient.java`                      | gRPC 远程调用                     |

## 附录 C：面试"讲项目" 5 分钟话术模板

> 我们约会的 match-service 用了 **D0 + D1 双队列** 做推荐。
>
> D1 是每天 07:00 UTC 的定时任务，**离线给用户预生成 240 张卡片**——基于最近 30 天右划数据学习偏好画像（age/beauty 高斯 + race 概率），然后用公式 **0.45×偏好 + 0.30×颜值 + 0.15×距离 + 0.10×活跃度** 打分排序，写到 Redis LIST。
>
> D0 是**实时兜底**——用户消费完队列 / 新用户 / D1 没跑成功时触发。**没有偏好**，用 4 层渐进召回 L0→L3（先同人种同龄→再放宽）保证总有候选。
>
> 消费侧 App 拉卡片时 LPOP 一次拿 5 张；用 Redis SET 二次过滤掉已 swipe 的；队列空就触发 D0 实时补。
>
> 关键设计点：① 离线预生成减轻 DB 压力；② 渐进召回保证冷启动；③ 二次过滤处理生成/消费的时差；④ Redisson 分布式锁保证 cron 多实例唯一；⑤ BH/DH 双池混合（算法用户 vs 真人）。

— 完 —
