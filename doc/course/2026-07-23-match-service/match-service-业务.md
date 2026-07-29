# match-service

| **功能编号** | **功能名称** | **描述** | **优先级** |
| --- | --- | --- | --- |
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
| F013 | **LikeVisitor Executor** | 每 1 分钟扫描到期 `dh_interaction_task` UPSERT + 硬删 | P1 |
| F014 | **MatchOutboxRetry** | 每 30 秒重试 PENDING 的 `match_outbox` 副作用 | P0 |

| **档位** | **每日右划次数** | **每日可划卡片** | **每日 Super Hi** |
| --- | --- | --- | --- |
| FREE(普通) | 5 | 50 | 0 |
| WEEKLY(周) | 10 | 80 | 0 |
| MONTHLY(月) | 15 | 120 | 1 |
| YEARLY(年) | 15 | 120 | 1 |

## **GetTodayFeed**

### **入口**

```python
FeedService.getTodayFeed(userId, count)
```

---

### **第一步：参数校验**

```python
if (count <= 0) count = props.getDefaultCount()  *// 默认 5*

if (count > props.getMaxCount()) count = props.getMaxCount()  *// 上限 20*
```

---

### **第二步：配额检查**

```python
tier = paymentServiceClient.getSubscriptionTier(userId)
if (quotaService.isCardsExhausted(userId, tier)) {
	return GetTodayFeedRespVO { cards: [], exhausted: true }
}
```

配额即每日可划卡片

---

### **第三步：获取配额快照**

```python
quota = quotaService.snapshot(userId, tier)
remaining = quota.dailyCardLimit() - quota.dailyCardUsed()
need = Math.min(count, remaining)
```

- `remaining <= 0` → 返回空列表

---

### **第四步：while 循环拉取卡片**

```python
while (result.size() < need && safetyRounds < 8) {
	safetyRounds++
	feedKey = match:feed:<userId>
	batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize)
	if (batch == null || batch.isEmpty()) {
		*// 队列空 → 触发冷启动重建*
		coldStartService.buildAndPush(userId)
		batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize)
		if (batch == null || batch.isEmpty()) break
	}
}
```

**最大 8 轮安全轮次**，防止死循环。

---

### **第五步：二次过滤（已划过卡片）**

```python
swipedKey = match:swiped:<userId>

for (String s : batch) {

parsed = props.parseFeedElement(s)  *// 解析 targetId:userType*

targetIds.add(parsed[0])

}

for (Long tid : targetIds) {

Boolean hit = stringRedisTemplate.opsForSet().isMember(swipedKey, tid)

swipedHits.add(hit)

}

*// 命中的丢弃，未命中加入 result*

for (int i = 0; i < batch.size() && result.size() < need; i++) {

if (swipedHits.get(i)) continue  *// 已划过 → 丢弃*

result.add(card)

}
```

**目的**：Feed 队列是异步构建的，用户可能已划过，必须二次过滤。

---

### **第六步：批量获取用户资料**

```python
ids = result.stream().map(CardVO::getTargetUserId).toList()

profiles = userServiceClient.batchGetProfile(ids)

for (CardVO card : result) {

p = profiles.get(card.getTargetUserId())

card.setNickname(p.getNickname())

card.setAge(p.getAge())

card.setBio(p.getBio())

card.setPhotoKeys(List.of(p.getAvatar().getOriginalKey()))

*// DH 用户 distance = -1（不展示距离）*

}
```

---

### **第七步：返回**

```python
return GetTodayFeedRespVO {

cards: result,

exhausted: result.isEmpty()

}
```

---

## **冷启动链路（子流程）**

### **入口**

```python
ColdStartService.buildAndPush(userId)
```

---

### **第一步：获取用户画像**

```python
profiles = userServiceClient.batchGetProfile(List.of(userId))

if (profiles.isEmpty()) return 0

user = profiles.get(0)

userGender = user.getGender()

targetGender = oppositeGender(userGender)  *// 男→女，女→男*

userAge = user.getAge() > 0 ? user.getAge() : 25

userBeauty = 60  *// 默认值，proto 暂无*
```

---

### **第二步：构造 exclude 列表**

```python
exclude = recaller.excludeUserIds(userId)  *// 已划过的用户*
```

---

### **第三步：双池召回**

```python
*// DH 池 — 渐进扩范围*

dhPool = recaller.recallDhPoolD0(

userId, targetGender, exclude,

userAge, userBeauty, user.getPreferredLocation()

)

*// BH 池 — 严格条件一次*

bhRaw = recaller.recallBhPool(

userId,

coldStartBhRadiusKm,           *// 距离范围*

userAge - bhAgeWindow,        *// 年龄下限*

userAge + bhAgeWindow,        *// 年龄上限*

userBeauty - bhBeautyWindow,  *// 颜值下限*

userBeauty + bhBeautyWindow,  *// 颜值上限*

List.of(),                    *// races（proto 暂无）*

bhActiveDays,                 *// 活跃天数*

bhExclude                     *// 排除列表（含自身）*

)
```

---

### **第四步：池内排序**

```python
*// BH 按字典序（created_at 降序，新用户优先）*

bhSorted = merger.sortBhForD0(bhRaw, isWithinWindow, null, userAge, newBhWindowDays)

*// DH 按字典序*

dhSorted = merger.sortDhForD0(dhPool, null, userAge)
```

---

### **第五步：按比例 merge**

```python
bhRatio = merger.computeBhRatioD0()  *// 默认 0.7（BH:D1 = 7:3）*

merged = merger.merge(bhSorted, dhSorted, bhRatio, d1QueueSize)
```

---

### **第六步：RPUSH 写入 Redis**

```python
elements = merged.entries.map(e -> formatFeedElement(e.candidateId(), e.userType()))

stringRedisTemplate.opsForList().rightPushAll(feedKey, elements)

stringRedisTemplate.expire(feedKey, feedTtlSeconds)

**格式**：`"targetUserId:userType"`（例：`"123456:2"`）
```

---

## **数据结构**

```python
*// Redis LIST — Feed 队列*

Key:    match:feed:<userId>

Value:  ["targetId1:userType", "targetId2:userType", ...]

TTL:    feedTtlSeconds（滑动过期）

*// Redis SET — 已划过集合（供二次过滤）*

Key:    match:swiped:<userId>

Value:  {targetId1, targetId2, ...}
```

---

## **返回结构**

```python
GetTodayFeedRespVO {

cards: [

CardVO {

targetUserId:  目标用户 ID,

targetUserType: BH 或 DH,

nickname:     昵称,

age:          年龄,

bio:          简介,

photoKeys:    头像 key 列表,

distanceKm:   距离(仅 DH=-1)

}, ...

],

exhausted: false  *// true=配额用完或无可推用户*

}
```

---

## **涉及问题**

| **问题** | **答案** |
| --- | --- |
| Feed 为什么用 LIST 不用 SET？ | LIST 有序、可 LPOP 先进先出，SET 无序无法保证推荐顺序 |
| 为什么要二次过滤？ | Feed 队列异步构建，用户可能已划过，必须 SMISMEMBER 检查 |
| 为什么用 Redis SET 做已划过集合？ | QPS 高，Redis O(1) 比 MySQL 快，且是滑动历史 |
| 冷启动为什么懒加载？ | 用户不刷新就不重建，省资源 |
| BH 和 DH 双池的区别？ | BH = 新用户池（需要被看到），DH = 延迟匹配池（互关优先推） |
| 冷启动同步还是异步？ | 当前是同步调用，用户会卡顿，生产建议异步 |
| 为什么最多 8 轮？ | 防止死循环，二次过滤后有效卡片可能很少 |

## **Swipe**

### **入口**

```python
SwipeService.swipe(userId, targetUserId, direction)
```

### **第一步：校验**

1. userId == targetUserId → 抛异常，不允许自己划自己

2. direction 不是 LEFT/RIGHT → 抛异常

### **第二步：获取分布式锁**

```python
lockKey = lock:match:swipe:<userId>:<targetUserId>
lock.tryLock(5秒超时, 3秒自动释放)
```

- 防止同一用户对同一目标并发发起两次 swipe
- 获取失败 → 抛 `CONCURRENT_SWIPE` 异常

### **第三步：doSwipe()**

#### **3.1 幂等检查**

```python
existing = swipeHistoryManager.findByPair(userId, targetUserId)
```

- `existing != null`：已划过 → 查询 match 表 → 返回历史结果（不扣配额）
- `existing == null`：首次 → 继续

#### **3.2 查询目标用户类型**

```python
targetType = userServiceClient.getUserType(targetUserId)
```

- `targetType <= 0` → 抛异常
- `targetType != BH && targetType != DH` → 抛异常

#### **3.3 配额扣减**

获取用户订阅等级，根据等级来进行额度扣减

```python
tier = paymentServiceClient.getSubscriptionTier(userId)
if (direction == LEFT) {
	quotaService.consumeCardOnly(userId, tier)
} else {
	quotaService.consumeRightSwipe(userId, tier)
}
```

#### **3.4 写历史 + 触发匹配（事务内）**

```python
writeHistoryAndTrigger(userId, targetUserId, targetType, direction)
```

**逻辑**：

| **direction** | **targetType** | **操作** |
| --- | --- | --- |
| LEFT | - | INSERT swipe_history → 返回 |
| RIGHT | BH | INSERT swipe_history → 查 reverse → 互划? INSERT match : UPSERT like_record → 返回 |
| RIGHT | DH | INSERT swipe_history → scheduleDelayedMatch → 返回 |

**BH 互划逻辑**：

```python
reverse = swipeHistoryManager.findByPair(targetUserId, userId)
if (reverse != null && reverse.direction == RIGHT/SUPER_HI) {
// 互划 → 即时 match
INSERT match (ON CONFLICT DO NOTHING)
softDeleteByPair(双向 like_record)
} else {
// 单向 → UPSERT like_record
}
```

#### **3.5 标记已划**

```python
feedService.markSwiped(userId, targetUserId)
```

将 targetUserId SADD 到 Redis 集合 `match:swiped:<userId>`，Feed 消费阶段二次过滤。

### **第四步：释放锁**

```python
finally { lock.unlock() }
```

### **延迟匹配链路（仅 RIGHT + DH 触发）**

```python
DhDelayedMatchService.scheduleDelayedMatch(userId, dhId)
│
├─ delayMs = 随机 15s ~ 2min（均匀分布）
├─ fireAt = now + delayMs
└─ matchTaskScheduler.schedule(Runnable, fireAt)
│
└─ [fireAt 时刻执行]
│
├─ type = userServiceClient.getUserType(dhId)
├─ type != DH → skip（静默）
└─ type == DH → matchService.createMatch(userId, dhId, SWIPE_MATCH)
```

### **返回结构**

```python
SwipeRespVO {
matchId:  > 0  → 已匹配，返回 matchId
= 0  → 未匹配
idempotent: false  *// 代码里固定 false，无实际作用*
}
```

### 涉及问题

划卡幂等：分布式锁+查数据库

## Super Hight

### **入口**

SuperHiService.superHi(userId, targetUserId)

---

### **第一步：校验**

1. `userId == targetUserId` → 抛异常，不允许自己 Super Hi 自己

---

### **第二步：获取分布式锁**

lockKey = lock:match:swipe:<userId>:<targetUserId>

lock.tryLock(5秒超时, 3秒自动释放)

- 防止同一用户对同一目标并发发起两次 Super Hi
- 获取失败 → 抛 `CONCURRENT_SWIPE` 异常

---

### **第三步：doSuperHi()**

#### **3.1 幂等检查**

```python
existing = swipeHistoryManager.findByPair(userId, targetUserId)

if (existing != null && existing.direction == SUPER_HI) {

*// 已 SuperHi 过 → 查询 match 表 → 返回历史结果*

return matchManager.findByPair(pair[0], pair[1])

.map(m -> new SuperHiRespVO(m.getId(), 0, true))

.orElseGet(() -> new SuperHiRespVO(0L, 0, true));

}
```

- `existing != null && direction == SUPER_HI` → 已划过 → 返回历史 match 结果
- 其他情况 → 首次 Super Hi，继续

#### **3.2 查询目标用户类型**

```python
targetType = userServiceClient.getUserType(targetUserId)

if (targetType != BH && targetType != DH) {

throw MatchBizException(TARGET_USER_NOT_FOUND)

}
```

- 目标必须是 BH 或 DH 类型，否则抛异常

#### **3.3 配额扣减 + 金币消费**

```python
tier = paymentServiceClient.getSubscriptionTier(userId)

giftLimit = SubscriptionTierConst.dailySuperHiLimit(tier)

//先扣减赠送配额，划卡数、右划数、super hi配额；配额不足回滚配额，返回用金币扣减
charge = quotaService.consumeSuperHi(userId, tier, giftLimit, SUPER_HI_COIN_PRICE)

if (charge.needCoinCharge()) {

	idemKey = "super_hi:" + userId + ":" + targetUserId
	//金币扣减
	res = paymentServiceClient.consumeCoins(userId, charge.coinsUsed(), idemKey, "SUPER_HI")
	
	if (!res.ok()) {
		//扣减失败回滚划卡数、右划数
		quotaService.rollbackSuperHi(userId)  *// 回滚配额*
		
		throw MatchBizException(SUPER_HI_INSUFFICIENT)
	
	}
	
	coinsUsed = charge.coinsUsed()
	
}
```

**配额结构**：

| **字段** | **说明** |
| --- | --- |
| `giftLimit` | 订阅等级对应的每日免费 Super Hi 次数 |
| `charge.coinsUsed()` | 超出免费次数后需要的金币数量 |
| `charge.needCoinCharge()` | 是否需要金币支付 |
| `idemKey` | 金币扣减幂等 key，防止重复扣金币 |

#### **3.4 写历史 + 立即触发 match（事务内）**

```python
@Transactional(rollbackFor = Exception.class)

writeHistoryAndTrigger(userId, targetUserId, targetType)
```

**逻辑**：

```python
*// 写 SUPER_HI 历史*

entity.setUserId(userId)

entity.setTargetUserId(targetUserId)

entity.setTargetUserType(targetType)

entity.setDirection(SUPER_HI)

entity.setSwipedAt(now)

swipeHistoryManager.insert(entity)

*// 立即创建 match（无需检查对方是否回划）*

match = matchService.createMatch(userId, targetUserId, SWIPE_SUPER_HI)
```

#### **3.5 标记已划**

```python
feedService.markSwiped(userId, targetUserId)
```

将 `targetUserId` SADD 到 Redis 集合 `match:swiped:<userId>`，Feed 消费阶段二次过滤。

---

### **第四步：释放锁**

```python
finally { lock.unlock() }
```

---

### **返回结构**

```python
SuperHiRespVO {

matchId:   > 0  → 已匹配，返回 matchId

= 0   → match 创建失败（极端情况）

coinsUsed: 本次消耗的金币数量（0 = 使用订阅赠送）

idempotent: true  *// 幂等返回时为 true*

}
```

---

## **SuperHi vs Swipe 对比**

| **维度** | **Swipe** | **SuperHi** |
| --- | --- | --- |
| **触发条件** | direction = LEFT/RIGHT | direction = SUPER_HI |
| **配额类型** | 普通划卡配额（每日卡片数） | SuperHi 专属配额（订阅赠送 + 金币） |
| **BH 互划匹配** | 需要查 reverse，双划才 match | **无需检查，立即 match** |
| **DH 延迟匹配** | schedule 延迟 match | **无需延迟，立即 match** |
| **金币消费** | 无 | 有（超出免费次数后扣金币） |
| **幂等 key** | 无 | 金币扣减用 `super_hi:userId:targetUserId` |

---

## **涉及问题**

| **问题** | **答案** |
| --- | --- |
| SuperHi 为什么立即 match？ | SuperHi 是付费动作，用户花钱了，无论对方是否喜欢都直接建立连接 |
| Swipe 为什么要查 reverse，SuperHi 不需要？ | Swipe 免费，要双方互喜才匹配；SuperHi 付费，等同于"强买强卖" |
| SuperHi 配额扣减失败怎么办？ | 先扣配额，金币不足时回滚配额，保证一致性 |
| 金币扣减幂等怎么做？ | 用 `super_hi:userId:targetUserId` 作为幂等 key |
| 事务边界是什么？ | `writeHistoryAndTrigger` 加 `@Transactional`，写 history 和 create match 在同一事务 |

## **ListMatches**

```python
gateway → MatchGrpcService.listMatches(userId, pageSize)
                    ↓
            matchManager.listByUser(userId, pageSize)  // 查 match 表
                    ↓
            userServiceClient.batchGetProfile(partnerIds)  // 批量获取对方资料
                    ↓
            组装 ListMatchesResp 返回
```

```python
// 1. 查用户的所有 match 记录
matches = matchManager.listByUser(userId, pageSize)

// 2. 提取对方用户 ID
for (MatchEntity m : matches) {
    otherId = m.getUserIdLow() == userId ? m.getUserIdHigh() : m.getUserIdLow()
    partnerIds.add(otherId)
}

// 3. 批量获取对方资料
profiles = userServiceClient.batchGetProfile(partnerIds)

// 4. 组装返回
for (MatchEntity m : matches) {
    MatchVO {
        matchId:        m.getId(),
        partnerUserId:  otherId,
        partnerNickname: p.getNickname(),
        partnerPhotoKeys: [p.getAvatar().getOriginalKey()],
        matchedAt:     m.getMatchedAt().toEpochMilli(),
        source:        m.getSource()  // SWIPE/SUPER_HI/DH_DELAYED 等
    }
}
```

## **GetQuota**

```python
gateway → MatchGrpcService.getQuota(userId)
                    ↓
        paymentServiceClient.getSubscriptionTier(userId)  // 获取订阅等级
                    ↓
        quotaService.snapshot(userId, tier)  // 获取配额快照
                    ↓
        组装 GetQuotaResp 返回
```

## **D0 冷启动实时召回**

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
而 D1 是 **DEL + RPUSH** 覆盖，因为 D1 是个性化的"今天最好的"，必须用最新结果覆盖。
> 

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

## **D1 日更离线生成**

## likeListOfMe

## **完整链路：`listLikesOfMe` → `assembleLikes`**

*// 第 40 行：入口*

```markdown
public ListLikesOfMeRespVO listLikesOfMe(long userId, int pageSize) {
```

**第一步：查数据库**

*// 第 43 行：从 like_record 表查出谁点赞了 userId*

```markdown
List<LikeRecordEntity> entities = listLikesPaged(userId, pageSize);
```

此时 `entities` 里只有原始数据，比如：

| **from_user_id** | **liked_at** | **like_content** |
| --- | --- | --- |
| 1024 | 2026-07-24 17:00 | null |
| 2048 | 2026-07-24 16:30 | "你很可爱" |

**第二步：批量拿用户资料**

*// 第 45 行：调 user-service，把 [1024, 2048] 传进去，一次性拿回两个用户的信息*

```markdown
List<LikeVO> likes = assembleLikes(entities);
```

在 `assembleLikes` 内部（第 97 行）：

```markdown
List<UserProfileProto> profiles = userServiceClient.batchGetProfile(fromIds);
```

只调用 **1 次** gRPC，就把 1024 和 2048 的用户资料都拿回来了。

**第三步：组装返回** 回到第 46-49 行，拼成最终响应：

```markdown
ListLikesOfMeRespVO resp = new ListLikesOfMeRespVO();

resp.setLikes(likes);                    *// 点赞列表（含用户信息）*

resp.setTotalUnread(likes.size());       *// 未读数（简化版）*

resp.setNextPageToken(...);              *// 游标分页 token*
```

## **最终返回给 App 的结构**

```markdown
{

	"likes": [
	
		{
		
			"fromUserId": 1024,
			
			"nickname": "Alice",
			
			"age": 25,
			
			"photoKeys": ["avatar/1024/202607/xxx.jpg"],
			
			"likedAtUnixMs": 1753363200000,
			
			"likeContent": ""
		
		},
		
		{
		
			"fromUserId": 2048,
			
			"nickname": "Bob",
			
			"age": 28,
			
			"photoKeys": ["avatar/2048/202607/yyy.jpg"],
			
			"likedAtUnixMs": 1753359600000,
			
			"likeContent": "你很可爱"
		
		}
		
	],
	
	"totalUnread": 2,
	
	"nextPageToken": "1753359600000"

}
```

## **一句话说清楚**

`listLikesOfMe` 是入口，查「谁点赞了我」；`assembleLikes` 是组装器，把冰冷的数据库记录加上好看的用户信息（昵称/年龄/头像），变成 App 能直接展示的 VO。

## **ListVisitsOfMe**

同上

## RecordVisit

当user-service或者post-service调用这个的时候，异步执行记录

## **核心逻辑拆解**

**第一步：取参数**

```markdown
long viewer = request.getViewerUserId();   *// 谁来看*

long target = request.getTargetUserId();   *// 看了谁*
```

**第二步：自我访问短路**

```markdown
if (viewer == target) {

responseObserver.onNext(RecordVisitResp.newBuilder().setOk(true).build());

responseObserver.onCompleted();

return;

}
```

**这是关键设计** — 用户看自己的主页不算访问，直接返回 ok，不再走后续流程。避免给用户自己刷访问量。

**第三步：异步记录**

```markdown
likeVisitService.recordVisitAsync(viewer, target);
```

把「viewer 看了 target」这条记录扔到异步队列里（Outbox + 调度器），**不等落库就立即返回**。这是性能优化 — 不能让用户因为一次"浏览主页"等数据库写完。

**第四步：返回结果**

- 成功 → `setOk(true)`
- 失败 → `setOk(false)` + 日志告警，但**不抛异常给上游**（gRPC 的设计哲学是：业务异常用业务字段表达）

## **设计意图总结**

| **设计点** | **目的** |
| --- | --- |
| 自我访问短路 | 防止自己刷自己的访问量 |
| `recordVisitAsync` 异步 | 不阻塞用户浏览路径，写库放后台 |
| `setOk(false)` 而非抛异常 | 降级处理，写库失败不影响主链路 |
| `try-catch` 包住 | 任何异常都安全降级为 `ok=false`，绝不让上游拿到 RPC 错误 |

## **对比「点赞」的入口（参考）**

你应该能在同一个文件里看到 `recordLike` 方法，它的模式几乎一样：

- 自己给自己点赞 → 直接返回
- 异步扔队列 → 立即返回
- 失败降级 → `setOk(false)`

这是 match-service 写入路径的**统一范式**：**「异步化 + 自我短路 + 降级不抛」**。

## **DH ONLINE 计划、DH OFFLINE 计划**

```
                ┌────────────────────────────────────────────┐
                │  match-service 内的两个 scheduler（定时器） │
                └────────────────────────────────────────────┘
                                │
      ┌─────────────────────────┴─────────────────────────┐
      ▼                                                   ▼
OnlinePlanGenerator                          OfflinePlanGenerator
@Scheduled(60_000ms)                         @Scheduled(1_200_000ms)
Redisson 锁 online_sweep                     Redisson 锁 offline_sweep
      │                                                   │
      ▼                                                   ▼
runOnlinePlan()                                runOfflinePlan()
      │                                                   │
      │  im.listOnlineUsers(cursor, now)                  │  im.listRecentOfflineUsers(...)
      │  拿最近 1 分钟在线的 BH                            │  拿离线 ≥ 20 min 的 BH
      │                                                   │
      └───────────────────────┬───────────────────────────┘
                              ▼
                  generateOne(userId, scene)
                              │
                              │  1. 类型闸：必须是 BH
                              │  2. cooldown 闸（在线场景）/ lastScene 闸（离线场景）
                              │  3. 拿用户画像（性别/年龄）
                              │  4. 排除已 swipe 的 DH
                              │  5. 24h 配额闸（按 dh_like_cap / dh_visit_cap）
                              │  6. 决定本次生成几个任务
                              │  7. user.listDhCandidates() 召回一批候选 DH
                              │  8. 按 visit/like 比例拆分
                              │  9. execute_time 在 [now, now+windowMin] 之间随机
                              ▼
                dh_interaction_task 表（任务进库）
                              │
                              ▼
            LikeVisitorTaskExecutor（另一个 scheduler）
                              │
                              │  定时扫表，把 execute_time ≤ now 的任务取出来
                              │  调 LikeVisitService 真正写入 like_record / visit_record
                              ▼
              like_record / visit_record 表（埋点数据）
                              │
                              ▼
            BH 打开 App 看到"有人访问/点赞了我"
```

**Online / Offline 这两个 scheduler 是 match-service 里的"DH 水军调度器"**：

- **Online**: 在线 BH 正在滑动 feed 时，**1 分钟 drip 一次**穿插 DH 互动，提升此刻体验
- **Offline**: 离线 ≥ 20 min 的 BH，**20 分钟收一波**攒任务，下次回 App 时通过推送/IM 拉回活跃

两者都通过 **Redisson 分布式锁**避免多实例重复生成，最终产出的 `dh_interaction_task` 会被 `LikeVisitorTaskExecutor` 真正执行（写 like_record / visit_record 表）。

## **LikeVisitor Executor**

### **节点 A：触发入口 — LikeVisitorTaskExecutor**
`LikeVisitorTaskExecutor(7.5) — 每 1 分钟扫到期待执行任务并执行 UPSERT + 硬删.`

**第一步：取参数 + 抢锁**

```python
public void run() {
RLock lock = redissonClient.getLock("lock:match:dh_plan:executor");
boolean acquired = lock.tryLock(0, 60, TimeUnit.SECONDS);  *// 非阻塞*
if (!acquired) {
log.debug("lock not acquired, skip");
return;
}
```

**三个关键设计**：

- `tryLock(0, ...)` → 抢不到直接跳过，**不堆积线程**
- `fixedDelay = 60_000L` → 上轮跑完再等 60s，**避免长事务重叠**
- 全局锁 `lock:match:dh_plan:executor` → 多实例部署只跑一个

调用**`runExecutor`**

---

### **节点 B：调度核心 — `runExecutor()`**

**第二步：扫描到期任务**

```python
Instant now = Instant.now();
//从数据库里获取到期的操作
List<DhInteractionTaskEntity> due = taskManager.scanDueTasks(now, props.getDhTaskScanLimit());

**SQL 锚点**：

SELECT * FROM dh_interaction_task

WHERE execute_time <= #{now}

ORDER BY execute_time ASC

LIMIT #{limit}
```

**第三步：逐条执行（核心循环）**

```python
for (DhInteractionTaskEntity t : due) {
	try {
		executeOne(t);              *// 执行业务*
		taskManager.hardDelete(t.getId());  *// 成功才删*
		executed++;
	} catch (Exception e) {
		log.warn("Execute DH task failed: id={} err={}", t.getId(), e.getMessage());
		*// 不删行,下一轮继续重试*
	}
}
```

> **亮点**：失败的任务留在表里，下一分钟 `scanDueTasks` 会再次捞到 → **天然重试机制**，不需要 RocketMQ 延迟消息。
> 

---

### **节点 C：单条执行 — `executeOne()`**

**第四步：决定 source 和 action**

把访问记录和喜欢记录插入表里

```python
@Transactional(rollbackFor = Exception.class)
public void executeOne(DhInteractionTaskEntity t) {
int sourceCode = t.getScene() == DhInteractionConst.SCENE_ONLINE
? LikeVisitSourceConst.DH_PLAN_ONLINE      *// 2*
: LikeVisitSourceConst.DH_PLAN_OFFLINE;    *// 3*
if (t.getAction() == DhInteractionConst.ACTION_LIKE) {
likeRecordManager.upsert(t.getFromUserId(), t.getToUserId(),
UserTypeConst.DH, sourceCode, t.getLikeContent());
} else if (t.getAction() == DhInteractionConst.ACTION_VISIT) {
visitRecordManager.upsert(t.getFromUserId(), UserTypeConst.DH,
t.getToUserId(), sourceCode);

}

}
```

**两个分支**：

| **action** | **落表** | **source** | **额外字段** |
| --- | --- | --- | --- |
| `ACTION_LIKE` (1) | `like_record` | `DH_PLAN_ONLINE` / `DH_PLAN_OFFLINE` | `like_content` |
| `ACTION_VISIT` (2) | `visit_record` | 同上 | — |

> `source` 字段标记"这条记录是 DH 模拟的，不是真人 swipe 的"，App 端可加徽章或过滤。
> 

---

### **节点 D：底层 UPSERT（last-wins vs counter）**

查表里

#### **LikeRecordManager.upsert —— last-wins**

#### **VisitRecordManager.upsert —— counter**

**两表对比**：

| **表** | **同 (from, to) 二次写入策略** | **UNIQUE 冲突处理** |
| --- | --- | --- |
| `like_record` | **覆盖** liked_at + content | 静默吞（不重试） |
| `visit_record` | **累加** visit_count + 1 | 静默吞（否则双重 +1） |

> **并发安全**：DB 层 `UNIQUE(from, to)` 兜底 + Java 层 `findByPair` 先查再写 + `DuplicateKeyException` 静默 = **最终一致**。
> 

---

### **节点 E：任务硬删 — 物理删除**

@Delete("DELETE FROM dh_interaction_task WHERE id = #{id}")

int hardDelete(@Param("id") Long id);

**第五步：执行成功后才删**

executeOne(t);

taskManager.hardDelete(t.getId());  *// 物理删除,不留历史*

> **为什么硬删而不是软删？** 因为 `dh_interaction_task` 是**短生命周期中间表**，执行完就没用了。配合 "失败留表自重试" 的设计 → **成功才删，失败保留** = 等价于消息确认机制。
> 

## outbox

# **`match_outbox` 是干啥的?——给"配对成功"那一刻的副作用打保险**

> 一句话:**MatchService 在事务里创建 match 之后,顺手往 outbox 表塞 3~4 条"待办副作用",把"业务结果"和"通知/会话/AI 开场白"在物理上解耦开。**
> 

---

## **先看清楚现状:为什么需要 outbox?**

```python
@Transactional(rollbackFor = Exception.class)

public MatchEntity createMatch(long userA, long userB, String source) {

*// 1. INSERT IGNORE match*

matchManager.insertIgnoreConflictWithLog(userA, userB, source);

*// 2. 同事务清理双向 like_record*

likeRecordManager.softDeleteByPair(userA, userB);

*// 3. 入 outbox 三条副作用(同步失败由后台 retry)*

enqueueSideEffects(match.getId(), userA, userB, source);

return match;

}
```

注意:**事务边界只到 step 3**。所有"创建 match + 清理 like"都是本地 PG 的事;**真正跨服务的事情(IM 建会话、发系统消息、DH 开场白)一个都没做**,只是写了 3 条 outbox 记录。

### **如果不用 outbox 会怎样?**

最直白的写法是:

```python
@Transactional

public MatchEntity createMatch(...) {

insertMatch(...);

imClient.ensureConversation(userA, userB);   *// RPC,可能 5s 超时*

imClient.sendSystemMsg(userA, ...);          *// RPC*

imClient.sendSystemMsg(userB, ...);          *// RPC*

triggerDhOpening(...);                       *// RPC*

}
```

立刻踩三个坑:

| **坑** | **后果** |
| --- | --- |
| **下游 IM 抖动 1 秒** | 本地事务持有锁 1 秒,DB 连接被吃光,所有滑动卡死 |
| **IM 那边建会话失败** | 整个 `@Transactional` 回滚 → **match 记录也丢了**,但用户明明已经划卡成功,体验上诡异 |
| **发了一半 IM 失败** | 两条系统消息一条发出去了、一条没发出去,数据不一致,排查极痛苦 |

---

## **outbox 在做什么?——把"业务事实"和"跨服务副作用"切开**

`enqueueSideEffects()` 一共塞了 4 类 outbox(`createMatch` 走 SWIPE 路径会塞 3 类):

| **字段值** | **含义** | **谁来执行** |
| --- | --- | --- |
| `action = ENSURE_CONVERSATION` | 在 IM 服务里给 A↔B 建会话 | `MatchOutboxService.deliver()` 调 im-service 的 gRPC |
| `action = SYSTEM_MSG` ×2 | 双方各发一条"你们配对了"的系统消息 | 同上,调 im-service |
| `action = DH_OPENING` | 触发数字人(AI)给用户发开场白 | 同上,但**只在 userB 是 DH 端时**才有意义 |

每条 outbox 长这样(`MatchOutboxEntity`):

```python
new MatchOutboxEntity()
.setMatchId(matchId)            *// 关联到 match 表*
.setAction("ENSURE_CONVERSATION")
.setPayloadJson("{...}")        *// RPC 要的参数,JSON 序列化*
.setAttempts(0)
.setNextRetryAt(Instant.now())  *// 立即可投*
.setStatus("PENDING");          *// 等后台捞*
```

**关键设计点:`match_outbox` 这条 INSERT 是在 `@Transactional` 里写的,跟 INSERT match 共用同一个事务**。所以:

```python
INSERT match ─┐

              ├─ 共用本地事务,原子提交

INSERT outbox ┘
```

> **要么 match + outbox 一起落库,要么一起回滚**。这彻底杜绝了"match 有了但 outbox 没写"的不一致。
> 

---

## **那谁去消费 outbox?——投递链路**

```python
                    ┌─────────────────────────────┐
 MatchService       │  match_outbox 表            │
 createMatch()  ───►│  status=PENDING, payload=…  │
  (本地事务)        └────────────┬────────────────┘
                                │
              ┌─────────────────┼──────────────────┐
              ▼                 ▼                  ▼
       MatchOutboxService    MatchOutboxRetry    运维/DBA
       (主动触发/补单)        (30s 兜底巡检)       (查 retry_count)
              │                 │
              └────► deliver() ─┘
                          │
                          ▼
                  调 im-service gRPC
                          │
            ┌─────────────┴─────────────┐
            ▼             ▼             ▼
       EnsureConv    SendSystemMsg   TriggerDhOpening
       status=DELIVERED
       (or RETRY + attempts++)
```

两个触发点:

1. **`MatchOutboxService.deliver()`**:事务提交后立即拉一批 PENDING 去发,作为"热路径"。
2. **`MatchOutboxRetry`**:每 30s 兜底一次,处理进程挂掉 / 下游抖动期间积压的记录。

只要 `status ≠ DELIVERED`,理论上永远重试——`retry_count` 只是给运维看哪条消息卡得久。

---

## **设计上几个关键判断**

### **1. 为什么 outbox 写在本地事务里?(而不是事务后再写)**

如果:

commit match → 再写 outbox

那么:

commit 成功 → 进程崩 → outbox 没写 → 配对了但没建会话 → 用户卡住

**outbox 跟业务表同事务**,等于把 outbox 当成业务的"承诺书":事务提交 = match 落库 + 承诺会去执行副作用,这两件事**永远原子**。

### **2. payload 为什么要 JSON 序列化?**

因为 outbox 是异步消费的,**消费时 RPC 的字段定义可能已经升级过**。JSON 当 schema-less payload 写库,等于打了个时间戳快照,即使后面改了 `EnsureConversationReq` 的字段,老的 outbox 也能照常解码发出去。

### **3. 为什么要 "INSERT IGNORE match" + "softDeleteByPair"?**

这俩是业务本地动作,跟 outbox 完全无关:

- `INSERT IGNORE`:并发滑动同一对(A 滑 B 同时 B 滑 A)时,只有一条 match 赢。
- `softDeleteByPair`:原本"双向 like_record"代表暗恋升级成 match,暗恋记录要被清掉。

**这两步必须在事务里**,否则就会出现"配对了但暗恋记录还在,被前端当成还能再滑"的怪事。

### **4. 为什么 `DH_OPENING` 走 outbox 而不是同步调?**

DH 开场白是 AI 模型生成首条消息,**耗时 1~3 秒很正常**。如果同步调:

- 用户配对请求卡 3 秒才返回 → 体验差
- AI 服务挂了 → match 事务回滚 → 用户白高兴一场

走 outbox 后:**match 立刻返回,AI 那边慢慢生成,生成完再 IM 推送给用户**。用户看到的是"配对成功 → 弹一条系统消息 → 几秒后收到 DH 的招呼",丝滑。

### **5. `createMatchImmediateWithSideEffects()` 是啥?**

```python
public MatchEntity createMatchImmediateWithSideEffects(long userA, long userB, String source) {
	MatchEntity match = createMatch(userA, userB, source);
	*// 触发一次 outbox 重试(让 retry 进程跑一次)*
	return match;
}
```

> 这是给**DH 延迟匹配**场景用的——"DH 跑了一段时间终于算出 B 喜欢 A,需要主动建 match"。它内部还是走 `createMatch`,outbox 同样会写,只是入口路径不同。
> 

---

## **一句话总结**

> **`match_outbox` 是 match 事务里的"承诺书"**——配对业务结果落库的同时,把"建会话 / 发系统消息 / 触发 DH 开场白"这些跨服务副作用拆出来异步执行,既保证业务一致,又不会被下游 IM / AI 的抖动拖垮主流程。
>