# Match Service 业务流程详解

> 本文是 Match Service 的主代码学习文档，以当前工作区源码、Proto、Flyway migration 和配置为事实来源。每个功能都包含完整调用链、编号执行步骤、关键代码、事务提交点、失败分支和最终结果。

## 一、统一入口与分层

Match Service 在 `MatchGrpcService` 中实现 8 个公开方法：

```text
GetTodayFeed
Swipe
SuperHi
ListMatches
GetQuota
ListLikesOfMe
ListVisitsOfMe
RecordVisit
```

本服务的 Proto 直接携带 userId，没有像 Post Service 那样统一从 gRPC Context 提取身份。因此入口使用的是请求字段，身份真实性依赖上游 Gateway。

调用分层：

```text
MatchGrpcService
  → 参数/枚举转换、VO 转 Proto、异常映射
  → Service
  → Manager / recommend component
  → Mapper / Redis / user/payment/im gRPC
```

## 二、功能与后台流程清单

| 编号 | 功能/流程 | 入口 |
| --- | --- | --- |
| F001 | 获取今日推荐 | `FeedService.getTodayFeed` |
| F002 | 普通划卡 | `SwipeService.swipe` |
| F003 | Super Hi | `SuperHiService.superHi` |
| F004 | 查看 Match | `MatchGrpcService.listMatches` |
| F005 | 查看配额 | `QuotaService.snapshot` |
| F006 | 查看 Like | `LikeVisitService.listLikesOfMe` |
| F007 | 查看 Visit | `LikeVisitService.listVisitsOfMe` |
| F008 | 上报 Visit | `LikeVisitService.recordVisitAsync` |
| J001 | D1 日更队列 | `D1QueueScheduler` |
| J002 | Match Outbox | `MatchOutboxRetry` |
| J003 | DH 延迟匹配 | `DelayedMatchTaskExecutor` |
| J004 | Super Hi 恢复 | `SuperHiRecoveryScheduler` |
| J005 | ONLINE/OFFLINE 计划 | 两个 Plan Scheduler |
| J006 | DH Like/Visit 执行 | `LikeVisitorTaskExecutor` |

## 三、F001 获取今日推荐

### 3.1 完整调用链

```text
MatchGrpcService.getTodayFeed
  → FeedService.getTodayFeed
    → PaymentServiceClient.getSubscriptionTier
    → QuotaService.isCardsExhausted / snapshot
    → Redis LPOP personal feed
    → Redis SISMEMBER swiped set × N
    → 队列空：ColdStartService.buildAndPush
      → UserServiceClient.batchGetProfile
      → CandidateRecaller.excludeUserIds
      → recallDhPoolD0 / recallBhPool
      → FeedMerger sort + merge
      → Redis RPUSH feed
    → UserServiceClient.batchGetProfile
    → CardVO
  → GetTodayFeedResp
```

### 3.2 第一步：数量归一化

入口把 `userId` 和 `count` 原样交给 `FeedService`。Service：

```java
if (count <= 0) count = props.getDefaultCount();
if (count > props.getMaxCount()) count = props.getMaxCount();
```

默认值通常为 5，最大值通常为 20，真实值以 Nacos `MatchProperties` 为准。

### 3.3 第二步：订阅和配额预检查

1. 调用 payment-service 获取订阅档位。
2. `QuotaService.snapshot()` 读取 UTC 当天 Redis HASH。
3. 根据档位常量得到 cards 上限。
4. 已使用数达到上限时立即返回空列表和 `exhausted=true`。
5. 否则计算：

```text
remaining = dailyCardLimit - dailyCardUsed
need = min(count, remaining)
```

获取 Feed 不扣 cards；只是限制本次最多返回多少张。

### 3.4 第三步：循环消费 Redis LIST

最多循环 8 轮，直到拿够 `need`：

1. 计算还缺多少张。
2. 对 `putao:match:feed:<userId>` 执行批量 `LPOP`。
3. LIST 为空时在当前请求线程调用 D0 重建。
4. 重建后再 `LPOP` 一次。
5. 仍为空则退出。

LIST 元素格式：

```text
<targetUserId>:<targetUserType>
```

`LPOP` 成功后元素已从队列永久移除。

### 3.5 第四步：解析和已划过滤

1. `MatchProperties.parseFeedElement()` 解析 ID 和类型。
2. 无法解析的元素跳过。
3. 对每个目标逐个调用：

```java
stringRedisTemplate.opsForSet()
        .isMember(MatchRedisKey.swiped(userId), targetId);
```

4. 命中 swiped SET 的目标丢弃。
5. 未命中目标转成只含 ID、类型和 DH 距离 `-1` 的 `CardVO`。
6. 数量不足时进入下一轮 LPOP。

当前代码注释写“SMISMEMBER”，实际是 N 次 `SISMEMBER`。

解析还有一个真实风险：`targetIds/types` 会跳过坏元素，但后续循环仍以原 `batch.size()` 下标读取，坏元素可能造成下标错位或越界。

### 3.6 第五步：D0 冷启动重建

`ColdStartService.buildAndPush(userId)`：

1. 批量接口读取当前用户画像；不存在返回 0。
2. 取性别并计算目标性别。
3. 年龄缺失用 25。
4. user.proto 没有 beauty，中心值固定 60。
5. 从 PG 划卡历史得到排除 ID。
6. `recallDhPoolD0()` 逐步放宽 DH 条件召回。
7. 复制排除列表并加入自己，召回 BH。
8. BH 按新用户、年龄差、beauty 等字典序排序。
9. DH 按年龄差、beauty 等排序。
10. `FeedMerger.merge()` 按 D0 BH 比例交错、限量、去重。
11. 格式化为 Redis 元素。
12. `RPUSH` 到原 Feed key。
13. 设置 Feed TTL。

D0 是追加写而不是覆盖写，也没有用户级锁。

### 3.7 第六步：批量补齐展示资料

1. 收集结果中的目标 ID。
2. 一次 `userServiceClient.batchGetProfile(ids)`。
3. 构造 `userId → profile` Map。
4. 逐卡补昵称、年龄、简介、头像。
5. 资料缺失的卡片仍保留，只是展示字段为空。

### 3.8 第七步：结果语义

```java
resp.setCards(result);
resp.setExhausted(result.isEmpty());
```

因此 exhausted 既可能表示配额耗尽，也可能表示没有候选、画像缺失、Redis 队列为空或过滤后无结果。

### 3.9 失败和一致性

| 位置 | 当前行为 |
| --- | --- |
| payment 订阅失败 | Client 按 FREE 降级 |
| Redis 配额/Feed 失败 | 请求失败 |
| D0 user-service 失败 | 通常得到空候选 |
| 卡片已 LPOP 后资料失败 | 不放回 Feed |
| 并发空队列 | 可能重复 D0 追加 |

## 四、F002 普通划卡

### 4.1 完整调用链

```text
MatchGrpcService.swipe
  → Proto direction 转 LEFT/RIGHT
  → SwipeService.swipe
    → 自划/方向校验
    → Redisson pair lock
    → UserSwipeHistoryManager.findByPair
    → UserServiceClient.getUserType
    → PaymentServiceClient.getSubscriptionTier
    → QuotaService.consumeCardOnly / consumeRightSwipe
    → MatchTransactionService.recordSwipe @Transactional
      → INSERT swipe history
      → LEFT：结束
      → DH RIGHT：INSERT delayed_match_task
      → BH RIGHT：查反向 swipe
        → 非互划：UPSERT like_record
        → 互划：MatchService.createMatch
    → 失败：QuotaService.rollbackSwipe
    → 成功：FeedService.markSwiped
  → SwipeResp(matchId)
```

### 4.2 第一步：入口枚举转换

`MatchGrpcService` 只接受 Proto `LEFT` 和 `RIGHT`。`UNSPECIFIED` 或其他值在进入业务层前映射为 `INVALID_ARGUMENT`。

### 4.3 第二步：业务校验和锁

`SwipeService.swipe()`：

1. userId 等于 targetUserId，抛 SELF_OPERATION。
2. direction 不是内部 LEFT/RIGHT，抛参数错误。
3. 获取 `MatchRedisKey.lockSwipe(userId,targetUserId)`。
4. 最多等待 5 秒。
5. 不指定 lease，让 Redisson watchdog 自动续期。
6. 获取失败返回并发错误。
7. finally 中仅当前线程持锁时解锁。

Swipe 和 Super Hi 使用同一把 pair lock，同一用户对不会同时执行两种操作。

### 4.4 第三步：历史幂等短路

锁内先查 PG `user_swipe_history`：

- 已经有该方向用户对历史：不再调用 user/payment，不扣配额。
- 再按标准化 pair 查 Match。
- Match 存在返回已有 ID。
- Match 不存在返回 0。

该逻辑只判断“是否已经划过”，不会允许用户把历史 LEFT 改成 RIGHT。

### 4.5 第四步：目标类型与订阅

1. user-service 查询目标类型。
2. 只接受 BH 或 DH。
3. payment-service 查询当前用户订阅档位。

目标不存在/类型异常停止；payment Client 的具体降级策略决定是否按 FREE 继续。

### 4.6 第五步：Lua 原子预扣

#### LEFT

`consumeCardOnly()`：

```text
HGET cards
  → cards+1 是否超限
  → HINCRBY cards 1
  → key 无 TTL 时设置 TTL
```

#### RIGHT

`consumeRightSwipe()`：

```text
HGET right/cards
  → 检查 right 上限
  → 检查 cards 上限
  → 同时 HINCRBY right 和 cards
  → 设置 TTL
```

状态码 1 表示右划不足，2 表示 cards 不足。检查和修改在一段 Lua 中原子执行。

### 4.7 第六步：进入真实数据库事务

`SwipeService` 调用另一个 Bean：

```java
@Transactional(rollbackFor = Exception.class)
MatchTransactionService.recordSwipe(...)
```

调用经过 Spring 代理。

事务首先 INSERT 划卡历史：

```text
userId
targetUserId
targetUserType
direction
swipedAt
```

然后按分支继续。

### 4.8 第七步：LEFT 分支

LEFT 在写完历史后直接返回：

```text
不写 Like
不写 Match
不写延迟任务
matchId=0
```

事务提交后外层更新 swiped SET。

### 4.9 第八步：DH RIGHT 分支

目标为 DH 时：

1. 随机生成 15 秒～2 分钟 delay。
2. 构造 `delayed_match_task`。
3. `status=PENDING`、`attempts=0`。
4. `executeAt` 和 `nextRetryAt` 设为未来时间。
5. 写入 PG。
6. 返回 `matchId=0`。

划卡历史和延迟任务在同一事务中提交或回滚。

### 4.10 第九步：BH RIGHT 分支

1. 查询反向 `(target,user)` 划卡历史。
2. 反向方向为 RIGHT 或 SUPER_HI 才算 mutual。
3. 非 mutual：upsert 一条 `like_record`，来源为普通右划。
4. mutual：调用 `MatchService.createMatch()`。
5. 返回新建或已有 Match ID。

### 4.11 第十步：事务失败补偿

`recordSwipe()` 抛 RuntimeException 时：

```java
quotaService.rollbackSwipe(userId, direction);
```

Lua 在计数大于 0 时：

- cards -1；
- RIGHT 再 right_swipe -1。

随后原异常继续抛出。

如果进程在 Redis 扣减成功、进入 catch 前崩溃，Java 补偿不会执行。

### 4.12 第十一步：提交后缓存

数据库成功返回后：

```text
SADD putao:match:swiped:<userId> targetUserId
```

当前没有 try/catch，Redis 写失败会让接口报错，但 DB 事实已经提交。重试会走历史幂等并返回，且不会再次执行 `markSwiped`，因此 swiped 缓存可能长期缺失，直到队列消费时由其他机制过滤或重建。

## 五、J003 DH 延迟匹配

### 5.1 任务领取

`DelayedMatchTaskExecutor` 每 5 秒调用 `deliverDueTasks()`：

1. 生成当前时间。
2. 最多领取 100 条到期任务。
3. Manager/Mapper 使用 `SKIP LOCKED`。
4. 更新为 PROCESSING。
5. 写 workerId 和两分钟 lockedUntil。

唯一键 `(user_id,dh_user_id,source)` 防止重复调度。

### 5.2 单任务执行

1. 重新向 user-service 查询 dhUserId 类型。
2. 返回负值表示依赖不可用，抛异常重试。
3. 仍为 DH 时调用 `MatchService.createMatch()`。
4. 已不是 DH 时不创建 Match。
5. 两种正常情况都标记 DONE。

### 5.3 失败重试

失败时：

```text
attempts + 1
nextRetryAt = now + min(300s, 2^(attempts+1)*5s)
达到10次 → DEAD
否则 → PENDING
```

worker 崩溃后 lease 到期，PROCESSING 任务可再次被领取。

## 六、Match 创建与 J002 Outbox

### 6.1 `createMatch` 本地事务

`MatchService.createMatch()` 自身是 public `@Transactional` 方法。它可能从 `MatchTransactionService` 的事务中参与现有事务，也可能由延迟任务新开事务。

步骤：

1. `MatchManager.pair()` 将用户排序为 low/high。
2. 尝试 INSERT Match。
3. 唯一冲突时返回已存在 Match。
4. 如果不是首次创建，立即返回，不重复副作用。
5. 首次创建后软删除双方 Like。
6. 写 Outbox。

### 6.2 Outbox 入箱

稳定 event key：

```text
<matchId>:conversation
<matchId>:system:<userA>
<matchId>:system:<userB>
<matchId>:dh-opening
```

每条记录包含 action、JSON payload、attempts=0、nextRetryAt=now、status=PENDING。

JSON 序列化异常抛出，Match、Like 清理和 Outbox 一起回滚。

当前代码对 `SWIPE_MATCH` 和 `SWIPE_SUPER_HI` 都写 DH_OPENING 事件。Outbox 执行时如果双方都不是一 BH 一 DH，会正常跳过并 DONE。

### 6.3 Outbox 调度领取

`MatchOutboxRetry` 每 30 秒调用 `MatchOutboxService.deliver()`。

Mapper CTE：

```sql
SELECT id
FROM match_outbox
WHERE next_retry_at <= now
  AND (
    status='PENDING'
    OR (status='PROCESSING' AND locked_until <= now)
  )
ORDER BY next_retry_at,id
FOR UPDATE SKIP LOCKED
LIMIT ?
```

同一个 SQL 将领取行更新为：

```text
PROCESSING
locked_by=<当前进程 UUID>
locked_until=now+2min
```

### 6.4 三类 dispatch

#### ENSURE_CONVERSATION

解析 `user_id_a/user_id_b`，调用 IM 确保会话。

#### SYSTEM_MSG

解析 `to_user_id/content/message_type`，调用 IM 发送系统消息。

#### DH_OPENING

1. 解析双方 ID。
2. user-service 查询双方类型。
3. 恰好一方 DH、一方 BH 时确定 dh 和 target。
4. 先确保会话并拿 conversationId。
5. 调用 triggerDhOpening。
6. IM 返回 false 时视为失败。
7. 双方都是 BH 或都是 DH 时直接正常结束。

未知 action、非法 JSON、必填 ID 缺失都抛异常，不会静默 DONE。

### 6.5 DONE 与重试

- dispatch 成功：只有相同 workerId 持有 PROCESSING 行时才能标记 DONE。
- 失败：attempts+1，最大退避 1800 秒。
- 达到配置的 maxAttempts 后标记 DEAD。
- 更新状态时也校验 workerId，旧 worker 不能覆盖新租约结果。

### 6.6 当前下游状态

Match Service 的领取和重试已实现，但当前 im-service 没有实现三个目标 gRPC 方法。实际调用可能返回 UNIMPLEMENTED 并最终 DEAD。

`event_key` 也没有传给 IM，所以“IM 成功、标 DONE 前崩溃”会重复执行下游副作用。

## 七、F003 Super Hi 与 J004 恢复

### 7.1 完整调用链

```text
MatchGrpcService.superHi
  → SuperHiService.superHi
    → 自操作校验
    → pair lock
    → 历史幂等
    → find super_hi_operation
    → 首次：目标类型 + tier + Redis Lua 预留
    → insertOrGet operation(QUOTA_RESERVED)
    → resume
      → 必要时 payment.consumeCoins(operationKey)
      → COINS_CHARGED
      → MatchTransactionService.completeSuperHi @Transactional
      → COMPLETED(matchId)
    → markSwiped
  → SuperHiResp
```

### 7.2 第一步：锁和历史幂等

1. 禁止 userId==targetUserId。
2. 获取与 Swipe 相同的 pair lock，最多等待 5 秒。
3. 已有划卡历史时不创建 operation。
4. 查已有 Match，有则返回其 ID；没有返回 0。

### 7.3 第二步：寻找或创建 operation

operation key：

```text
super_hi:<userId>:<targetUserId>
```

先查 `super_hi_operation`。存在就从原状态 resume；不存在才：

1. 校验目标类型。
2. 查询订阅档位。
3. 调用 `consumeSuperHi` Lua。
4. 构造 QUOTA_RESERVED 操作。
5. `insertOrGet()` 利用唯一键处理并发。

### 7.4 第三步：Lua 配额预留

Lua 先查 quota HASH 中的 operation field：

- 已有值直接返回，避免重复扣。
- 检查 right/cards 上限。
- 同时增加 right/cards。
- 赠送 Super Hi 未用完时增加 `super_hi` 并返回 0。
- 否则返回金币价格。
- 写 operation marker。
- 设置 TTL。

### 7.5 第四步：金币分支

coinsUsed>0 且状态 QUOTA_RESERVED 时：

1. 使用相同 operationKey 调 payment。
2. RPC 异常：保存 lastError，保留状态并向上抛。
3. payment 明确失败：Lua rollback 配额，硬删除 operation，返回金币不足。
4. payment 成功：状态改为 COINS_CHARGED。

超时不能直接回滚，因为下游可能实际已经扣款。

### 7.6 第五步：本地完成事务

`MatchTransactionService.completeSuperHi(operation)`：

1. 查当前用户对划卡历史。
2. 不存在时 INSERT direction=SUPER_HI。
3. 调 `MatchService.createMatch(source=SWIPE_SUPER_HI)`。
4. 已有历史时先找 Match；没有 Match 仍补建。
5. 获取 matchId。
6. 同一事务将 operation 标记 COMPLETED 并保存 matchId。
7. 返回 matchId 和 coinsUsed。

### 7.7 第六步：提交后缓存

事务返回后尝试写 swiped SET。这里有独立 try/catch，失败只记录警告，不改变成功响应。

### 7.8 恢复任务

`SuperHiRecoveryScheduler` 每 30 秒：

1. 扫描 10 秒前仍可恢复的操作，最多 100 条。
2. 每条尝试获取同一个 pair lock，不等待。
3. 获取成功调用 `resume(operation)`。
4. 完成计数加一。
5. 失败记录警告，保留状态下轮继续。

当前恢复扫描没有全局 ShedLock，但每条 pair lock 防止相同操作并发 resume。

### 7.9 孤儿窗口

Lua 已写 operation marker、PG operation 尚未 insert 时崩溃，恢复 Scheduler 看不到 Redis 孤儿。需要对账或 marker 清理任务。

## 八、F004 查看 Match

### 8.1 逐步过程

1. gRPC 将 pageSize 默认 20、最大 50。
2. 请求中的 pageToken 没有读取。
3. `MatchManager.listByUser(userId,pageSize)` 查询 low/high 任一侧。
4. 按 `matched_at DESC,id DESC` 排序。
5. 对每条关系计算 partner：

```text
low==userId ? high : low
```

6. 收集 partnerIds。
7. 一次 `batchGetProfile()`。
8. 构造 partnerId→profile Map。
9. 按原 Match 顺序组装 ID、partner、时间、source。
10. 资料存在时补昵称和 avatar original key。
11. 响应没有设置真正 nextPageToken。

资料 RPC 整体失败时当前入口 catch 后返回 INTERNAL，而不是返回缺资料 Match；只有批量 Client 自身降级为空列表时才会保留空资料关系。

## 九、F005 查看配额

### 9.1 逐步过程

1. gRPC 读取 userId。
2. payment Client 查询订阅 tier。
3. `QuotaService.snapshot()` 生成 UTC 日期 key。
4. `HGETALL`/entries 读取 quota HASH。
5. cards/right/super_hi 缺失或非数字按 0。
6. 根据 tier 常量得到各每日上限。
7. 附加固定 Super Hi 金币价格。
8. gRPC 将内部 tier 转成字符串。

Redis 失败没有 PG 回退，直接进入 INTERNAL。

## 十、F006 查看谁喜欢了我

### 10.1 逐步过程

1. gRPC 未传正 pageSize 时默认 20。
2. Service 再 clamp 到 1～50。
3. 请求 pageToken 没有传给 Service。
4. `LikeRecordManager.findToUser()` 查询有效 Like，按 likedAt 倒序。
5. 收集 fromUserId。
6. 批量获取用户资料。
7. 逐条组装时间、likeContent、昵称、年龄、头像。
8. 资料缺失时仍保留 Like。
9. `totalUnread=当前页大小`。
10. `nextPageToken=末条 likedAt 毫秒`。

因为下一次请求不消费 token，所以只能重复第一页。

## 十一、F007 查看谁访问了我

流程与 Like 类似：

1. pageSize 默认 20，Service clamp 1～50。
2. pageToken 未使用。
3. 查询当前用户收到的 Visit，按最近访问时间倒序。
4. 批量补访问者资料。
5. 组装最近访问时间和 visitCount。
6. `totalUnread=当前页大小`。
7. next token 只生成不消费。

Visit 表按 `(from,to)` 聚合。当前 `VisitRecordManager.upsert()` 是：

```text
SELECT existing
  → 有：Java visitCount+1 后 UPDATE
  → 无：INSERT，唯一冲突只记录 debug
```

不是原子 `ON CONFLICT visit_count=visit_count+1`，并发访问可能丢增量。

## 十二、F008 上报 Visit

### 12.1 逐步过程

1. gRPC 读取 viewer 和 target。
2. viewer==target 直接返回 `ok=true`。
3. 非自访问调用 `recordVisitAsync()`。
4. Spring 把任务提交到 `visitRecordExecutor`。
5. gRPC 立即返回 `ok=true`。
6. 异步线程再次防御自访问。
7. 调 `VisitRecordManager.upsert()`。
8. 数据库异常在异步线程内 catch，只记录 WARN。

只有“提交线程池”同步抛异常时入口返回 `ok=false`。任务已入队但未执行时服务重启，数据会丢失。

## 十三、J001 D1 日更推荐

### 13.1 Scheduler

每天 UTC 07:00：

1. 计算昨日 UTC 范围。
2. 构造日期锁 `lock:match:d1:<yyyymmdd>`。
3. `tryLock(0,1h)`，多实例只有一个执行。
4. 查询昨日有 Swipe 的用户，LIMIT 500。
5. 逐用户调用 `D1Generator.generateForUser()`。
6. 单用户异常不阻断其他用户。

代码累加了 offset，但 Mapper 方法没有 offset 参数；若每次刚好返回 500 条，循环会再次查询相同第一批，可能无限重复。

### 13.2 单用户生成

1. 再次确认昨日确实有 Swipe。
2. 获取用户画像，缺失跳过。
3. 计算目标性别和年龄。
4. `PreferenceBuilder.build()` 用最近行为构建偏好。
5. 偏好无效时回退年龄、beauty 默认分布。
6. 排除全部已划目标和自己。
7. 分别召回 DH/BH。
8. `Ranker.scoreDh/scoreBh()` 打分。
9. 各取 Top `d1QueueSize`。
10. 根据 ID 恢复候选对象。
11. `FeedMerger` 按 D1 BH 比例合并。
12. `DEL` 原 Feed。
13. `RPUSH` 新 Feed 并设置 TTL。

`buildMutualMap()` 当前返回空 Map，互相喜欢加分未生效。`DEL+RPUSH` 中间不是原子。

## 十四、J005 ONLINE/OFFLINE DH 互动计划

### 14.1 ONLINE Scheduler

每 1 分钟：

1. 尝试全局 online sweep lock，lease 60 秒。
2. 读取 online cursor。
3. cursor 缺失默认一分钟前。
4. 落后超过 30 分钟时也重置到一分钟前。
5. 调 IM 查询 cursor～now 在线用户，最多 5000。
6. 立即把 cursor 推进到 now。
7. 逐用户生成 ONLINE 计划。

IM Client 若失败并降级为空，cursor 仍推进，该窗口不会补扫。

### 14.2 OFFLINE Scheduler

每 20 分钟：

1. 获取 offline sweep lock，lease 30 分钟。
2. 计算离线阈值上界。
3. 读取 offline cursor 和 lookback floor。
4. 调 IM 查询最近离线用户，最多 5000。
5. 推进 cursor。
6. 逐用户生成 OFFLINE 计划。

### 14.3 单用户计划生成

`generateOne(userId,scene)`：

1. user-service 确认必须是 BH。
2. ONLINE 检查 cooldown key。
3. OFFLINE 检查 lastScene 不能已经是 OFFLINE。
4. 检查 PG 是否已有同场景任务。
5. 获取用户画像。
6. 排除已划过 DH。
7. 统计最近 24 小时 DH Like/Visit。
8. 计算剩余额度。
9. 从配置范围随机本次任务数。
10. 召回两倍数量 DH 候选并随机打乱。
11. 按 visitRatio 和剩余额度分配 Visit/Like。
12. 每条任务的 executeTime 随机落在未来窗口。
13. Like 随机选择配置文案。
14. 批量 INSERT `dh_interaction_task`。
15. ONLINE 写 cooldown。
16. 写 lastScene。

任务入库和 Redis cooldown/lastScene 不在一个事务，部分失败可能导致重复计划或计划被抑制。

## 十五、J006 DH Like/Visit Executor

### 15.1 调度和执行

每分钟：

1. 获取全局 executor lock，lease 60 秒。
2. 扫描到期任务，数量受配置限制。
3. 对每条调用 `executeOne()`。
4. LIKE：upsert `like_record`。
5. VISIT：upsert `visit_record`。
6. 业务写成功后硬删除任务。
7. 失败不删，下一轮重试。

### 15.2 事务为何无效

`executeOne()` 标注 `@Transactional`，但调用方式是同一对象内部：

```text
DhInteractionPlanService.runExecutor
  → this.executeOne
```

没有经过 Spring 代理。因此业务 UPSERT 和 `hardDelete()` 也不在同一个有效事务中。

崩溃窗口：

```text
Like/Visit 已写
  → 进程崩溃
  → 任务未删
  → 下轮重复执行
```

Like upsert主要更新同一记录；Visit 会再次增加 visitCount。

## 十六、存储与状态清单

### PostgreSQL

| 表 | 用途 |
| --- | --- |
| `user_swipe_history` | 权威划卡历史 |
| `match` | 唯一匹配关系 |
| `match_outbox` | 匹配后 IM 副作用 |
| `delayed_match_task` | DH 持久化延迟匹配 |
| `super_hi_operation` | Super Hi 恢复状态 |
| `like_record` | 单向 Like |
| `visit_record` | 聚合 Visit |
| `dh_interaction_task` | DH 自动互动任务 |

### Redis

| Key | 类型 | 用途 |
| --- | --- | --- |
| `putao:match:quota:<uid>:<date>` | HASH | 每日配额和 Super Hi marker |
| `putao:match:feed:<uid>` | LIST | 消费型推荐队列 |
| `putao:match:swiped:<uid>` | SET | 已划二次过滤 |
| `putao:match:pref:<uid>` | HASH | 已定义但当前未读写 |
| `putao:match:dh_plan:*` | STRING | cursor、cooldown、last scene |
| `lock:match:*` | Redisson | pair、D1、计划与执行器锁 |

### MQ

当前没有 MQ producer/consumer。可靠延迟和 Outbox 使用 PostgreSQL durable queue。

## 十七、实现状态与风险

| 项目 | 状态 |
| --- | --- |
| Swipe 事务代理 | 已生效 |
| 配额原子迁移 | 已实现 Lua |
| BH 互划统一 Match/Outbox | 已实现 |
| DH 延迟任务持久化 | 已实现 |
| Outbox lease/claim | 已实现 |
| Super Hi 恢复 | 已实现 |
| IM 三个副作用 RPC | 下游未实现 |
| IM event key 幂等 | 未实现 |
| Visit 可靠异步 | 未实现 |
| Match/Like/Visit 真分页 | 未实现 |
| D1 超 500 用户分页 | 存在缺陷 |
| D1 原子替换 | 未实现 |
| Feed rebuild single-flight | 未实现 |
| DH Executor 有效事务 | 未实现 |

## 十八、代码索引

| 主题 | 主要类 |
| --- | --- |
| gRPC | `MatchGrpcService` |
| Feed/D0 | `FeedService`、`ColdStartService`、`CandidateRecaller`、`FeedMerger` |
| Swipe | `SwipeService`、`MatchTransactionService`、`QuotaService` |
| Match | `MatchService`、`MatchManager` |
| Outbox | `MatchOutboxService`、`MatchOutboxMapper`、`MatchOutboxRetry` |
| DH 延迟 | `DhDelayedMatchService`、`DelayedMatchTaskExecutor` |
| Super Hi | `SuperHiService`、`SuperHiOperationManager`、`SuperHiRecoveryScheduler` |
| 查询/访问 | `LikeVisitService`、`LikeRecordManager`、`VisitRecordManager` |
| D1 | `D1QueueScheduler`、`D1Generator`、`PreferenceBuilder`、`Ranker` |
| DH 互动 | `DhInteractionPlanService` 与三个 Scheduler |

