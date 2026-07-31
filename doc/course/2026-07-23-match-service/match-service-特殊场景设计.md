# Match Service 特殊场景设计

> 本文聚焦 Match Service 中具有系统设计价值的场景。用户功能见 `match-service-业务清单.md`，逐步代码执行过程见 `match-service-业务流程详解.md`。

## 一、场景总览

| 场景 | 核心矛盾 | 当前方案 |
| --- | --- | --- |
| D0/D1 双轨推荐 | 新用户无行为与老用户个性化需求不同 | D0 画像先验 + D1 行为偏好 |
| BH/DH 双池混排 | 真人供给有限、数字人补量但不能穿帮 | 独立召回/排序 + 比例 merge |
| Redis Feed 消费 | 低延迟拉卡与不重复展示 | LIST `LPOP` + swiped SET 二次过滤 |
| 每日配额 | 多计数并发扣减不能半成功 | Redis HASH + Lua 原子状态迁移 |
| Swipe 幂等与事务 | Redis 配额和 PG 事实跨介质 | pair lock + 历史幂等 + 本地事务 + 补偿 |
| DH 延迟匹配 | 需要拟人延迟又不能因重启丢任务 | PostgreSQL durable queue + lease |
| Match 副作用 | Match 不能被 IM 故障回滚 | Transactional Outbox + `SKIP LOCKED` |
| Super Hi | Redis、payment、PG 无法一个事务 | 可恢复 operation + payment 幂等键 |
| DH 模拟互动 | 自动 Like/Visit 需要自然且限频 | ONLINE/OFFLINE 计划 + 随机执行窗口 |

## 二、D0 与 D1 双轨推荐

### 2.1 为什么需要两套推荐

D0 面向没有足够行为样本的用户，只能依赖用户画像和业务先验；D1 面向已有划卡行为的用户，可以从最近右划学习偏好。把两者硬合并会导致：

- 新用户没有样本，模型结果不稳定；
- 老用户一直使用固定年龄/距离条件，无法个性化；
- 行为样本不足时缺少明确回退路径。

### 2.2 D0 当前实现

`ColdStartService.buildAndPush()`：

```text
用户画像
  → 目标性别、年龄、默认 beauty=60
  → 排除全部已划用户
  → DH 渐进扩范围召回
  → BH 距离/年龄/活跃条件召回
  → 两池各自字典序排序
  → 按 D0 BH 比例 merge
  → RPUSH 个人 Feed LIST
```

D0 直接由空 Feed 请求同步触发，没有单用户 rebuild lock。

### 2.3 D1 当前实现

`D1Generator.generateForUser()`：

1. 仅处理昨日有划卡的用户。
2. 读取用户画像。
3. `PreferenceBuilder` 用最近行为生成年龄、颜值等偏好。
4. 样本不足时回退用户画像先验。
5. 分别召回 DH/BH。
6. `Ranker` 分别打分并取 Top。
7. `FeedMerger` 根据偏好计算 BH 比例后混合。
8. `DEL + RPUSH` 覆盖个人 Feed。

预留的 mutual-like bonus 当前没有构建真实 Map，因此排序信号尚未实现。

### 2.4 当前规模化边界

- D1 活跃用户 Mapper 只有 `LIMIT 500`，Scheduler 的 offset 没传入 SQL，满 500 时会重复第一批。
- `DEL + RPUSH` 不是原子切换，读取方可能看到瞬时空队列。
- D0 追加写，多个并发重建可能产生重复。
- 更大规模可使用 keyset 分页、影子 LIST 原子 rename、每用户重建锁和任务分片。

## 三、BH/DH 双池混排与产品屏蔽

### 3.1 为什么独立召回

BH 与 DH 的供给、活跃度、距离和互动策略不同。先合成一个池再排序会让数量更充足的 DH 压制 BH，或为两类用户强行使用相同特征。

当前模式：

```text
BH：距离、年龄、活跃等条件召回
DH：性别、年龄、beauty、排除列表召回
  → 池内各自排序
  → FeedMerger 按比例交错
  → candidateId 去重
```

内部保留用户类型用于后续分支，但 Like/Visit 对外不暴露来源类型，保持统一产品体验。

### 3.2 真实性与风险

- DH 普通右划延迟匹配，避免每次立即成功。
- Super Hi 对 DH 立即匹配，符合付费能力语义。
- DH Like/Visit 分散到时间窗口并受 24 小时上限控制。
- 推荐比例和自动互动参数可配置，但过高比例仍可能被用户感知。

## 四、Redis LIST 消费型 Feed

### 4.1 为什么使用 LIST

个人 Feed 已经离线或实时生成好顺序，读取只需：

```text
LPOP N
```

优点是低延迟、天然向后推进；代价是元素弹出即消失。

### 4.2 二次过滤

队列生成和用户划卡可能发生在不同设备或不同时刻，因此消费时还要检查：

```text
putao:match:swiped:<userId>
```

PostgreSQL `user_swipe_history` 是权威事实，SET 只是快速过滤缓存。

### 4.3 当前风险

- 卡片弹出后资料 RPC 失败，不会放回。
- 用户只是看见但没有划卡，卡片仍已消费。
- 逐个 `SISMEMBER` 产生多次 Redis 往返。
- 空队列同步 D0 会增加首屏延迟并可能击穿。

若业务要求“展示确认后才消费”，需要 pending/ack 模型；若允许滑过即消费，当前 LIST 更简单。

## 五、Redis Lua 配额状态机

### 5.1 数据结构

```text
Key: putao:match:quota:<userId>:<yyyyMMdd UTC>
Type: HASH
Fields:
  cards
  right_swipe
  super_hi
  super_hi_op:<operationKey>
```

### 5.2 为什么不能 Java 先查后加

两个并发右划都可能读取到“还剩 1 次”，然后各自增加，突破上限。RIGHT 还同时涉及 cards 和 right swipe，分两条命令会产生半扣。

Lua 在 Redis 内一次完成：

```text
读取当前值
  → 检查所有上限
  → 同时 HINCRBY
  → 必要时设置 TTL
  → 返回明确状态码
```

### 5.3 Super Hi operation marker

`CONSUME_SUPER_HI` 先查 `super_hi_op:<operationKey>`：

- 已存在：返回上次计算的金币数，不重复扣配额。
- 不存在：扣 cards/right；赠送未用完则 super_hi+1 并返回 0，否则返回金币价格。
- 最后写 operation marker。

明确金币不足时，另一段 Lua 删除 marker 并回退对应计数。

### 5.4 一致性边界

Redis 配额和 PostgreSQL 划卡不能组成一个本地事务。普通 Swipe 使用“先扣 Redis、DB 失败后补偿”，但进程在两者之间崩溃仍可能产生配额已扣、划卡未落库。Super Hi 通过 operation 表和 payment 幂等进一步缩小风险，但 Redis marker 与操作表之间仍有孤儿窗口。

## 六、Swipe 并发、幂等和事务

### 6.1 pair lock

Swipe 与 Super Hi 共用：

```text
lock:match:swipe:<userId>:<targetUserId>
```

最多等待 5 秒，不设置固定 lease，让 Redisson watchdog 在 RPC/DB 执行期间续期。它只串行化相同方向的用户-目标操作，不阻塞不同目标。

### 6.2 历史幂等

拿锁后先查 `(userId,targetUserId)` 划卡历史：

- 已存在 Match：返回已有 Match ID。
- 没有 Match：返回 0。
- 不再扣配额或重复写历史。

### 6.3 有效事务边界

数据库写放在独立 `MatchTransactionService` public 方法：

```java
@Transactional
public SwipeRespVO recordSwipe(...) { ... }
```

调用经过 Spring 代理，划卡历史、DH 延迟任务、Match、Like 清理和 Outbox 可按分支形成真实本地事务。

### 6.4 BH 互划幂等

Match 使用标准化：

```text
low  = min(userA,userB)
high = max(userA,userB)
UNIQUE(low,high)
```

A→B 和 B→A 映射到同一用户对。冲突时读取已有 Match，不重复写 Outbox。

## 七、DH 持久化延迟匹配

### 7.1 为什么不能只用进程内 Timer

15 秒～2 分钟的任务如果只保存在 Java 内存：

- 发布或崩溃会丢；
- 多实例无法协调；
- 无法查询积压和重试。

当前 `delayed_match_task` 与 Swipe 历史在同一事务中插入。

### 7.2 状态与抢占

```text
PENDING
  → 到期扫描
  → PROCESSING + workerId + lockedUntil
  → 成功 DONE
  → 失败 PENDING + nextRetryAt
  → 达到10次 DEAD
```

领取使用 `FOR UPDATE SKIP LOCKED`，租约 2 分钟。worker 崩溃后，其他实例可在租约过期后重新认领。

### 7.3 执行时二次确认

任务到期后重新调用 user-service：

- 仍是 DH：幂等创建 Match。
- 已不是 DH：不创建 Match，任务标记 DONE。
- 服务不可用：重试而不是误判完成。

## 八、Match Transactional Outbox

### 8.1 本地原子性

首次创建 Match 时同一 PostgreSQL 事务执行：

```text
INSERT match
  → 软删除双向 like_record
  → INSERT conversation event
  → INSERT system message for A
  → INSERT system message for B
  → INSERT dh-opening event
```

JSON 序列化失败会抛异常并回滚，而不是写空 payload。

### 8.2 多实例安全领取

Mapper 使用一个 CTE：

```sql
SELECT ... FOR UPDATE SKIP LOCKED
  → UPDATE status=PROCESSING
  → locked_by=<worker>
  → locked_until=now+2min
  → RETURNING rows
```

只有持有 worker lease 的实例可以标记 DONE 或重试。

### 8.3 至少一次与下游缺口

IM 成功、Match Service 标记 DONE 前崩溃时事件会重放。`event_key` 在本地有唯一索引，只能防止重复入箱，不能阻止下游重复执行。

生产闭环需要：

1. Proto 把 event key 传给 im-service。
2. im-service 按 event key 幂等。
3. im-service 实现 EnsureConversation、SendSystemMessage、TriggerDhOpening。

当前三个 RPC 在 im-service 尚未实现，事件会重试并最终 DEAD。

## 九、Super Hi 可恢复 Saga

### 9.1 跨越三个系统

```text
Redis 配额
  → payment-service 金币
  → PostgreSQL Swipe/Match/Outbox
```

Spring 事务只能覆盖最后一段。

### 9.2 操作状态

```text
QUOTA_RESERVED
  → 不需要金币：直接本地完成
  → 需要金币且成功：COINS_CHARGED
  → 本地事务成功：COMPLETED(matchId)
```

operation key 同时用作：

- Redis quota marker；
- `super_hi_operation` 唯一键；
- payment-service 扣费幂等键。

### 9.3 恢复任务

每 30 秒扫描更新超过 10 秒的未完成操作，每次最多 100 条。恢复前重新获取同一 pair lock，然后按状态继续：

- QUOTA_RESERVED：用相同幂等键确认/完成扣费。
- COINS_CHARGED：继续本地 Match 事务。
- COMPLETED：直接复用结果。

### 9.4 不确定错误处理

支付明确余额不足时可以回滚配额；支付超时不能直接回滚，因为下游可能已经扣款。保留操作并重试是避免“双重扣费或免费成功”的关键。

## 十、DH 模拟互动真实性

### 10.1 计划与执行分离

在线计划每分钟扫描，离线计划每 20 分钟扫描。计划生成只写 `dh_interaction_task`，Executor 每分钟执行到期 Like/Visit。

### 10.2 防穿帮约束

- 只给 BH 用户生成。
- ONLINE 使用 cooldown。
- OFFLINE 使用 last scene 防止同一离线期重复。
- 任务表按场景去重。
- 排除已划过 DH。
- 检查最近 24 小时 DH Like/Visit 上限。
- 候选随机打乱。
- Like/Visit 按比例分配。
- execute time 均匀散布到未来窗口。
- Like 文案从配置模板随机选择。

### 10.3 当前可靠性风险

在线计划或离线 IM 查询失败时，客户端可能返回空列表，但 cursor 仍推进，导致时间窗口被永久跳过。

Executor 中：

```text
runExecutor()
  → this.executeOne() @Transactional
  → hardDelete(task)
```

同类调用不经过 Spring 代理，业务 UPSERT 和删任务没有原子事务。业务已写、任务未删时崩溃会重试。Like UPSERT基本幂等；Visit 会再次增加次数。

## 十一、设计原则总结

Match Service 根据业务事实的价值选择不同方案：

- 划卡、Match、Outbox、延迟任务：PostgreSQL 权威事实。
- Feed、配额、短期 cursor/cooldown：Redis 高频状态。
- 普通跨介质 Swipe：锁、幂等和补偿。
- 付费 Super Hi：可恢复 operation 和支付幂等键。
- Match 后副作用：至少一次 Outbox，不阻塞 Match 成立。
- 非核心 Visit 上报：当前仍是易丢的本机异步，明确标为待升级。

