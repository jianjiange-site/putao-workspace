# match-service 面试问答

> 配套：[`README.md`](./README.md)（学习入口）、[`prd.md`](./prd.md)（业务功能）、[`knowledge.md`](./knowledge.md)（技术原理）

## 使用说明

- **高频问题**：面试中 80% 概率被问到，准备 STAR 法则标准答案。
- **追问**：每个问题准备 2~4 个追问答案，对应"如果对方想得更深"的场景。
- **场景问题**：面试官让你设计一个系统时，怎么组织答案。
- **项目讲解话术**：30 秒 / 2 分钟 / 5 分钟三个版本。

---

## 项目讲解话术

### 30 秒版（一句话定位）

> 我做的 match-service 是 dating app 首页"卡片右划即喜欢"全链路的唯一权威服务，负责**双池召回生成 240 张卡片队列 → 串行化划卡 → 按 BH/DH 矩阵触发 match → 把跨服务副作用丢进 outbox 兜底 → 后台跑 DH 模拟 like/visit 让用户感到被关注**。技术上重点是 Redis LIST+LPOP 队列模型、进程内 TaskScheduler 延迟匹配、跨服务 RPC 失败时的 Outbox 兜底三个核心机制。

### 2 分钟版

> 我们做的是一个 dating app 后端微服务。首页是个卡片 feed，用户可以左划不喜欢、右划喜欢、Super Hi 立即配对。
>
> 我负责的 match-service 有三大业务：① **生成卡片队列**，分 D0 冷启动（实时双池召回）和 D1 日更（美东 03:00 cron 全量重写），双池分别是 DH（数字人，渐进扩范围凑满 240）和 BH（真人，严格条件一次召回到不够就不够），最后按比例 merge，**BH 不足用 DH 补齐**，队列容量恒 240。② **划卡动作处理**，关键点是用 Redisson 锁 + `user_swipe_history` UNIQUE 索引实现幂等，BH 互划即时 match，**DH 右划要走 15s~2min 进程内延迟匹配**（防一秒回应穿帮）。③ **Match 副作用编排**，跨服务调 im-service 建会话 + 发系统消息用 **Outbox 模式**兜底 — 本地事务写 match 表 + 入 outbox，后台每 30 秒扫描 PENDING 任务指数退避重试，用户视角永远不会因为 IM 抖动而看到 swipe 失败。
>
> 技术上我踩过几个有意思的坑：Redis LIST + LPOP 不保证"消费快照 = 召回快照一致"，所以加了 **`match:swiped` SET 二次过滤**；match 表用 `(user_id_low, user_id_high)` UNIQUE 兜底重复 match，触发冲突打 ERROR 日志而非抛异常；DH 模拟 like/visit 三个 scheduler 用 **Redisson 锁**支持多实例分布式部署，配上 cooldown / last_scene 闸 + execute_time 30min 均匀分布防穿帮。

### 5 分钟版（完整版，详讲到每个关键决策）

> （参照上面的 2 分钟版 + PRD/knowledge 中各知识点的 why 部分）

---

## 高频问题

### Q1：请介绍一下你做的 match-service

**考察点**：项目讲解能力；是否真正理解业务边界

**标准答案**（用 STAR）：

- **Situation**：dating app 首页是卡片 feed（类似 Tinder），用户滑动卡片，喜欢 / 不喜欢对方。BH（真人）和 DH（数字人）两类用户都要展示给 BH。
- **Task**：我们做 match-service 承载**首页 + 配对 + 互动**全链路，包括卡片队列生成、划卡动作、配对触发、IM 副作用编排、Like/Visit 互动列表、DH 模拟计划。
- **Action**：核心模块：① `D1Generator` + `D1QueueScheduler` 每日 cron 全量重算 240 张卡片；② `FeedService` 用 Redis LIST + LPOP 即消费 + 二次过滤兜底；③ `SwipeService` Redisson 锁串行化 + UNIQUE 兜底幂等；④ `DhDelayedMatchService` 进程内 TaskScheduler 15s~2min 延迟匹配防穿帮；⑤ `MatchService` + `match_outbox` 模式做 IM 副作用兜底；⑥ `DhInteractionPlanService` 三个 scheduler（ONLINE / OFFLINE / Executor）做 DH 模拟 like/visit。
- **Result**：日均拉 feed 卡片 N 万张，配对成功率 X%，DH 计划命中率 Y%。

**追问**：

- Q: 为什么不把 match-service 拆成两个服务（推荐 + 配对）？
  A: 推荐和配对强耦合 — 划卡配对后的 like/match 数据是推荐的偏好画像数据源；同事务写多张表让服务自治完整。

- Q: match-service 与 user-service / im-service 的边界？
  A: match-service **只通过 gRPC** 调用 user-service（取 profile / 候选）/ payment-service（订阅 + 金币）/ im-service（建会话 + 系统消息 + 在线状态）。**绝不直连** user-service 的 PG / Redis（CLAUDE.md 红线）。

- Q: 如果让你重做，你会改什么？
  A: ① match.source 改 Java enum 时机可以再早一点（首次新增就引入 enum）；② DH 模拟计划 executor 加批量化 UPSERT（当前单条事务，量大时可能拖慢 executor）；③ D1 cron 失败时启动期加速补跑，避免全量压到 03:00。

---

### Q2：D0 冷启动和 D1 日更有什么区别？为什么需要两套？

**考察点**：推荐系统分冷启动 / 成熟期的常规设计

**标准答案**：

| 维度 | D0 | D1 |
|------|----|----|
| 触发 | 实时（拉 feed 时如果队列空就触发） | 离线（cron 美东 03:00 UTC 07:00） |
| 偏好来源 | 用户自身画像 (prior) | 用户 30 天右划画像分布 |
| 池内排序 | 字典序硬排序（4 级 BH / 3 级 DH） | 打分公式（0.45/0.30/0.15/0.10 + 两个 +0.20 bonus） |
| 数据源 | 实时调 `user-service.listDhCandidates/nearbyUsers` | 实时调 + 30 天 swipe_history 聚合 |

**核心区别**：D0 没有用户历史，所以没有"右划画像分布"可言，必须用 prior（用户自身画像）作为偏好来源；D0 排序也走字典序（可解释、可调），不走打分公式（D0 用户没行为数据，preferenceSim 全是 prior 退化的常量）。

**追问**：

- Q: D1 cron 失败怎么办？
  A: Redis LIST 不被覆盖 → 用户继续消费旧队列 → 旧队列耗尽 → GetTodayFeed 触发 D0 实时重建。对 App 透明。**没有"D1 失败兜底队列"概念**。

- Q: 为什么 D0 BH 池严格条件一次，BH 不够就不够？
  A: BH 质量是产品体验核心。放宽 BH 条件会引入低质量真人，**宁愿少真人也不能放进低质真人**。merge 阶段 DH 自动补齐。

- Q: D1 排队为什么不返回"近期 like 过我的 BH"作为加权重？
  A: 这是 mutual_like_bonus 在做的事 — 在打分公式里 +0.20 加性 bonus（不是加性权重），让"对方 RIGHT 过我的 BH"显著拉升曝光。

---

### Q3：为什么 BH 右划 DH 要延迟 15s ~ 2min？

**考察点**：业务理解深度 + 进程内调度设计能力

**标准答案**：

- **业务原因**：DH（数字人）一秒回应会被用户立刻意识到是机器人。但等太久（>分钟级）用户已经划走下一张卡，丧失"互相喜欢"的即时反馈价值。
- **窗口选择**：15s 下限保证"不是一秒回应"；2min 上限保证用户在 App 内能感知到。
- **实现选择**：进程内 Spring `TaskScheduler.schedule()`（底层 `ScheduledExecutorService`，内存小顶堆，μs 级精度）。量级小（10k DAU × 5 右划/天 × 50% DH = 25k/天 ≈ 0.3/秒，in-flight < 600），不值得为它建 PG 表 + 扫表 cron。
- **重启丢任务的 trade-off**：服务重启时 in-flight 任务丢失，用户感知是"她没喜欢我"，符合 dating app 常见预期，**不引入持久化兜底**。
- **Super Hi 对 DH 不延迟**：用户付了费就期望立即看到结果，产品意图覆盖"防穿帮"。

**追问**：

- Q: 为什么不用 Redis ZSet 替代 TaskScheduler？
  A: ZSet 需要 listener 周期性扫，重启不丢任务 — 但复杂度更高（要处理 ZSet 过期、capacity、ZADD 频率等）。当前 in-flight 量级不值得这么重。如果未来量级到 100k DAU 或更长窗口，可以加 ZSet 兜底。

- Q: 进程内调度，重启就丢 — 不怕关键 match 漏掉？
  A: 漏掉的 match 用户感知是"她没喜欢我"，**符合 dating app 常见预期**（不是每个右划都互相喜欢）。这个窗口里 match 的数据可由下次右划触发 — 召回过滤的 swipe_history 已经把 target 排除了。

- Q: 15s 和 2min 的均匀分布为什么用 `ThreadLocalRandom.nextLong(15_000, 120_001)`？
  A: `nextLong(min, max)` 范围是 `[min, max)`，所以传 `120_001` 才能保证最大值包含 120_000（即 2 min 整）。`ThreadLocalRandom` 是每个线程独立的随机源，多线程并发不会重复。

---

### Q4：Match 副作用为什么要用 Outbox 模式？

**考察点**：分布式事务 / 最终一致性 / 跨服务容错

**标准答案**：

- **业务诉求**：match 创建成功后**必须**触发 IM 副作用（建会话 + 发系统消息），但用户**永远不应该**因为 IM 抖动看到 swipe 失败。
- **方案对比**：

| 方案 | 实现 | 缺点 |
|------|------|------|
| 本地消息表 + 定时扫表 | PG 表存待发消息 | 跟 outbox 一致 |
| RocketMQ 事务消息 | 事务型 MQ | 增加 MQ 依赖，副作用不是高吞吐 |
| **Outbox 模式** | PG 表 + 异步重试 worker | 简单、本地事务原子、幂等投递 |

- **match-service 的 Outbox**：
  - 本地事务同时写 `match` 表 + `match_outbox` 表（原子）；
  - 后台 `MatchOutboxRetry` scheduler 每 30 秒扫 PENDING 任务；
  - 失败时指数退避（5s → 10s → 20s → 40s → 80s → 160s → 320s → 600s 封顶 30 min）；
  - 达 `max_attempts` 后置 DEAD，需要人工捞数据。
- **幂等性保障**：IM 副作用本身幂等（EnsureConversation 是幂等建会话，SystemMessage 允许重复发送），outbox 重试安全。

**追问**：

- Q: 为什么不直接用 MQ 事务消息？
  A: ① match-service 之前只用 PG + Redis，不引入新基建依赖；② 副作用消息不是高吞吐（match 频率有限）；③ 失败需要重试，MQ 也需要一个 worker（outbox 一样需要 worker）。
  A: 但如果将来要在多个服务间共享 match 事件（如 post-service 想知道"用户 match 成功"），建议上 MQ。

- Q: Outbox 重试时，任务会不会被多实例重复消费？
  A: 当前设计**没有显式去重**，依赖 IM 副作用本身幂等。如果 IM 出现非幂等副作用（如 SystemMessage 去重 id），需要在 outbox 加 status 字段标记 IN_PROGRESS，由 SELECT FOR UPDATE SKIP LOCKED 避免并发消费。

- Q: outbox 表无限增长怎么办？
  A: 当前实现只 status=DONE 的行定期清理（如保留 7 天）；status=DEAD 的行需要人工捞；status=PENDING 的 attempts 接近 max_attempts 时告警。

- Q: 为什么不用分布式事务（Saga / TCC）？
  A: 分布式事务复杂度高，对"建会话 + 发消息"这种轻量副作用过度设计。IM 副作用**幂等**，天然支持重试 — 不需要事务型一致性。

---

### Q5：D1 打分公式是怎么来的？为什么是 0.45/0.30/0.15/0.10？

**考察点**：推荐系统调参与实战经验

**标准答案**：

- **base_score 权重和 = 1.0**，是经验值默认。Nacos 可调，运营 A/B 试不同参数。
- **0.45 / preference_similarity**：因为"符合用户偏好"是命中 match 的第一要素（用户更可能 right-swipe 高匹配度的卡）。
- **0.30 / 颜值分归一化**：颜值在 dating 场景是强信号，但不能盖过偏好。
- **0.15 / 距离衰减**：距离是次要因素（D0 BH 池已经按距离强过滤过；D1 的 200km 半径下，距离带来的边际信息更弱）。
- **0.10 / 活跃度**：活跃度主要影响"对方是否会回话"，权重最低。
- **加性 bonus（不参与权重和）**：
  - `mutual_like_bonus +0.20`：对方 RIGHT/SUPER_HI 过 user — 明确双向感兴趣，优先推上去能直接产出 BH↔BH match。
  - `new_bh_bonus +0.20`：新人 BH 缺历史信号（没人对她右划过、活跃天数少），base 分先天低，加性 bonus 抵消系统偏差。
  - 两个 bonus 仅作用于 BH，DH 永远是 0。
  - 同一张 BH 可以**同时**触发两个 bonus（新人 + 对方喜欢过我 = +0.40），最大化曝光。
- **preference_similarity 实现**：age 高斯 PDF × beauty 高斯 PDF × race 占比（三项乘积）。

**追问**：

- Q: 为什么用高斯而不是简单的 (mean - x) / std？
  A: 高斯有"远离 mean 时权重指数衰减"的特性 — 比线性下降更"软"，允许用户稍微偏离偏好也有曝光。z>3 截断 → 完全 0，避免无意义计算。

- Q: 偏好画像的样本 < 10 时怎么处理？
  A: `PreferenceProfile.isValid() = false` → D1Generator 回退到 D0 prior（用户自身画像作为 center），仍能正常打分但精度退化。

- Q: race 占比只有一个值时怎么办？
  A: 回退到 0.2（默认中性值），避免全部 0 导致乘积为 0。

- Q: 如果让你调权重，你会怎么调？
  A: A/B 实验平台：分 5% 流量给不同权重组合（如 mutual_like_bonus 0.20 vs 0.30 vs 0.15），观察 BH→match 转化率。**先调 base 权重，再调 bonus**。

- Q: 为什么不直接用 LR / DNN 模型？
  A: MVP 阶段用可解释的线性公式（运营能调，能 debug）；DNN 训练成本高且样本量不足。**未来数据量足够时再做模型升级**。

---

### Q6：DH 模拟 like/visit 怎么防止用户察觉是机器人？

**考察点**：业务理解 + 真实感约束的设计能力

**标准答案**：

- **三个 scheduler 分工**：
  - `OnlinePlanGenerator` 每 1 分钟扫 `im:presence:online` ZSet 增量（用户新上线）；
  - `OfflinePlanGenerator` 每 20 分钟扫 `user_online_session` 增量（用户离线 ≥ 20 min）；
  - `LikeVisitorTaskExecutor` 每 1 分钟扫到期任务并 UPSERT + 硬删。
- **8+ 项 Nacos 可调约束**：
  - 单次生成 DH 数（ONLINE 5~10，OFFLINE 3~6）；
  - VISIT/LIKE 比例（60%/40%，visit 更日常）；
  - 24h DH like/visit 上限（15/25）；
  - cooldown / last_scene 闸（ONLINE 2h / OFFLINE 单次离线期最多 1 个）；
  - **execute_time 在 [now, now + 30min] 均匀随机分布**（关键防穿帮措施）；
  - like_content 文案池 JSON 列表（运营可热刷）；
  - exclude 已 like/visit 关联过的 DH（避免同一 DH 反复）。
- **关键**：execute_time 必须均匀分布。**如果 10 个任务都 execute_time = now，下一分钟 executor 一次性 insert 10 条 like_record，用户在 1 分钟内看到 10 个新 like —— 一眼穿帮。**
  均匀分散到 30 分钟内，每次打开 App 只看到 1~2 条新互动，符合真人行为。

**追问**：

- Q: DH 池不够时怎么 fallback？
  A: 直接跳过该用户，不报错。运营查 DH 池规模 / DH 数量。

- Q: cooldown 2h 太短 / 太长怎么办？
  A: 调整 Nacos `match.dh_plan.online_cooldown_seconds` — 实时生效。

- Q: 如果用户发现是机器人，会怎样？
  A: 24h like/visit 上限就是兜底 — 即使运营配错，单个用户 24h 内最多收到 15 个 DH like / 25 个 DH visit。

- Q: 不模拟行不行？全部交给真人。
  A: 新用户前 30 天几乎没有真人互动，**直接放空 Like of me / Visits of me 会让留存断崖式下跌**。DH 模拟是补"被关注感"的关键。

---

### Q7：match 表 `(user_id_low, user_id_high)` UNIQUE 是怎么设计的？为什么不是 `(user_id_a, user_id_b)`？

**考察点**：数据库索引设计与并发思考

**标准答案**：

- 同一对 `(a, b)` 与 `(b, a)` 视为同一 — match 没有方向。
- 用 `low = min(a,b), high = max(a,b)` 作为 UNIQUE 索引的左 / 右字段。
- 触发了 UNIQUE 约束：
  - **不抛异常给用户**（swipe RPC 不能因 bug 报错）；
  - **打 ERROR 日志**带丰富上下文（pair / existing_id / existing_source / new_source）；
  - **返回 existing match** — 调用方仍能拿到合法的 match id，UX 正常。
- 这是"上游召回过滤失效"的报警信号，**出现 ERROR 日志说明 user_swipe_history 与 exclude_user_ids 链路有 bug，需要排查**。

**追问**：

- Q: 为什么不用 Redis 锁兜底？
  A: 锁会拖慢所有 swipe 操作（高频写路径），而 UNIQUE 约束是 PG 本身的特性，O(1) 检查。

- Q: 触发 UNIQUE 时的 caller_stack 怎么拿？
  A: 当前实现没拿 caller_stack，是已知 gap。改进方案：在异常 catch 中 `new Exception().getStackTrace()` 取 top 5 帧，打 ERROR 日志。

- Q: 同一 user 多次右键对方，match 数量会增加吗？
  A: 不会 — swipe 接口本身幂等（同 user/target 第二次返回上次结果），且 user_swipe_history UNIQUE(user_id, target_user_id) 兜底。

---

### Q8：Redis LIST + LPOP 即消费模型怎么保证一致性？

**考察点**：缓存一致性 / 召回快照与消费快照的 gap 处理

**标准答案**：

- **Redis LIST 的语义**：LPOP 即弹出，LIST 内不可能重复 — 这是"消费阶段不可能重复"的保证。
- **不能保证的**："消费快照 ≠ 召回快照"。卡片在 LIST 里躺着时，用户可能在另一台设备 swipe 过同一 target — LPOP 出来还原样推给用户就会"刚划过又看到"。
- **解决方案**：`match:swiped:<user_id>` Redis SET 二次过滤。
  - swipe 接口同步 SADD（`feedService.markSwiped`）；
  - GetTodayFeed LPOP 后用 SMISMEMBER 校验，命中过滤的卡片直接丢弃，while 循环继续 LPOP 凑足 need；
  - SMISMEMBER 是 O(1) lookup，批量 5 个不到 1ms，代价可忽略。
- **不维护"已下发但未 swipe"的 SET**：LPOP 出来的卡片如未被 swipe（只 LPOP 未消费匹配），下次重建时允许再次进入候选池，**不算重复推荐**。

**追问**：

- Q: 二次过滤丢弃的卡片从 LIST 永久弹出，是浪费吗？
  A: 是接受的代价 — 极少发生（仅并发跨设备），且该卡片确实不该再出现。

- Q: Redis 7.0 之前 LPOP 不支持 count，怎么办？
  A: 用 pipeline 多次 LPOP（每次 1 个），或用 LRANGE 0 N + LTRIM 0 N 模拟。Redis 7.0+ 直接用 LPOP key count 一行解决。

- Q: 为什么不直接用 Redis Stream？
  A: Stream 是 Kafka-like 设计（消费者组 + ACK），单机场景复杂度高；LIST+LPOP 简单直接，能满足"每个用户独立消费"的场景。

---

### Q9：match-service 怎么保证跨服务一致性？

**考察点**：分布式系统一致性 / 微服务边界设计

**标准答案**：

| 场景 | 一致性策略 |
|------|-----------|
| match → im-service（建会话 + 系统消息） | Outbox 模式（本地事务 + 异步重试） |
| match → payment-service（订阅档位 / 扣金币） | 实时 RPC + 5min cache + 失败 fallback（按 FREE 处理） |
| match → user-service（取 profile / 候选） | 实时 RPC + 失败降级返回空集合 |
| 跨服务 gRPC 失败 | 不阻塞主流程；RPC 失败时降级 |

**关键红线**：match-service **绝不直连** user-service / payment-service / im-service 的 PG / Redis（CLAUDE.md 红线 #2）。所有跨服务访问走 gRPC。

**跨服务失败的处理哲学**：
- 主流程 RPC 失败 → **降级**（不抛给用户）；
- 副作用 RPC 失败 → **Outbox 兜底**（重试）；
- 关键校验 RPC 失败 → **拒绝服务**（如 Swipe 不知道 target 类型时拒绝）。

**追问**：

- Q: payment-service 的订阅档位如果更新了，match-service 的 5min cache 怎么办？
  A: 5min TTL 自然过期。或者 payment-service 主动发 invalidate 事件（设计层面已确认支持，但当前 MVP 未实现）。

- Q: 如果 outbox 任务连续失败 5 次，置 DEAD — 怎么处理？
  A: 人工捞数据 + 修复 + 重新入 outbox。当前没做运营工具支撑，是已知 gap。

- Q: Super Hi 扣金币时 RPC 失败怎么办？
  A: catch Exception → 回滚已扣的 cards/right_swipe 配额 + 抛 SUPER_HI_INSUFFICIENT。**保证用户的配额不被吞**。

---

### Q10：match-service 用了什么分布式锁？

**考察点**：分布式锁的使用场景和踩坑

**标准答案**：

| 锁 | TTL | 用途 |
|----|-----|------|
| `lock:match:swipe:<user>:<target>` | 5s | 单次 swipe 串行化（避免并发 swipe 同一对） |
| `lock:match:d1:<yyyymmdd>` | 1h | D1 cron 多实例防重 |
| `lock:match:dh_plan:online_sweep` | 60s | OnlinePlanGenerator 多实例防重 |
| `lock:match:dh_plan:offline_sweep` | 30min | OfflinePlanGenerator 多实例防重 |
| `lock:match:dh_plan:executor` | 60s | LikeVisitorTaskExecutor 多实例防重 |

**Redisson 的使用模式**：`tryLock(0, ttl, TimeUnit.SECONDS)` — waitTime=0 立刻抢锁，抢不到 skip 而不是阻塞。**三个 DH 计划 scheduler 抢不到锁直接 skip 本轮**（锁内已有人在跑），不阻塞等待。

**追问**：

- Q: 为什么不阻塞等待？
  A: scheduler 周期性触发，1 分钟 / 20 分钟一次。下一次还会重试 → 阻塞等待只浪费当前线程，且 scheduler 触发是周期性的，硬等会拖慢调度。

- Q: 如果 Redisson 不可用怎么办？
  A: 当前实现直接抛错 → scheduler skip — 用户感知是"这一轮没生成 DH 计划"。DH 计划是体验增强功能，丢失一轮用户感知不大。

- Q: tryLock(0, ...) vs tryLock(5, ...) 的选择？
  A: Swipe 接口用 5s waitTime（用户希望 swipe 一定能跑完）；scheduler 用 0 waitTime（周期性，跳过本轮没关系）。

- Q: 为什么不用 SETNX 自己实现分布式锁？
  A: Redisson 提供了完整实现（看门狗续期、RedLock 模式、可重入），自己实现容易踩锁过期 / 死锁 / 续期失败等坑。

---

### Q11：match-service 在 Redis 上有哪些数据结构？为什么这么设计？

**考察点**：Redis 数据结构选型 + Key 设计规范

**标准答案**：

| 数据结构 | 用途 | 为什么 |
|---------|------|--------|
| `quota:<user>:<yyyymmdd>` HASH | 当日配额 right_swipe / cards / super_hi | 多字段共享 key + HINCRBY 原子累加 |
| `feed:<user>` LIST | 240 张卡片队列 | LPOP 即消费 + 批量 LPOP count 支持 |
| `swiped:<user>` SET | 已 swipe 的 target | SMISMEMBER 二次过滤 O(1) |
| `pref:<user>` HASH | 偏好画像 cache | 暂未使用（预留） |
| `dh_plan:cursor:online/offline` STRING | 游标 epoch ms | 单字段递增 + 简单 SET/GET |
| `dh_plan:cooldown:<user>` STRING | ONLINE cooldown 闸 | EX 7200 自动过期 |
| `dh_plan:last_scene:<user>` STRING | "ONLINE" / "OFFLINE" | 单字段 |

**Key 命名规范**：`putao:<service>:<domain>:<id>` — 防止跨服务 key 撞车。

**TTL 策略**：
- quota HASH：36h（让 UTC 跨日 + 偶尔写入失败有冗余）；
- feed LIST：7 天（不活跃用户回归时旧队列已过期 → 触发实时 D0 重建）；
- swiped SET：永久（白名单注释：丢了可以 PG 重建）；
- cooldown STRING：2h（与 cooldown 业务时间一致）。

**追问**：

- Q: swiped SET 永久不删，会不会无限增长？
  A: 用户一个 target 只会 SADD 一次，SET 大小 = user 历史 swipe 总数。一年活跃用户大约 1000~5000 条 swipe，**单 user SET 内存 < 50KB**，可接受。

- Q: quota HASH 36h 过期，下一天用户跨 0 点划卡怎么办？
  A: 下一条 swipe 时 quotaKey 是新日期的 key，旧 HASH 自然过期失效，新 key 从 0 开始计数 — 这是预期的"配额刷新"语义。

- Q: feed LIST TTL 7 天后过期，用户回归怎么办？
  A: GetTodayFeed LPOP 触发 ColdStartService.buildAndPush 实时 D0 重建。对 App 透明。

- Q: 为什么不把 swiped SET 改成 ZSet（带时间戳）？
  A: 二次过滤只需要"在不在"语义，SET 的 O(1) 即可。ZSet 多维护 score 的代价不划算。

---

### Q12：SuperHi 和普通 Swipe 的核心区别是什么？

**考察点**：产品语义区分 + 实现差异

**标准答案**：

| 维度 | LEFT/RIGHT Swipe | SUPER_HI |
|------|------------------|----------|
| 语义 | 喜欢 / 不喜欢（被动等对方回） | **付费**立即硬匹配 |
| 配额消耗 | 1 张卡 + 0/1 次右划 | 1 张卡 + 1 次右划 + 1 次订阅赠送 / 100 金币 |
| 触发条件 | 视对方类型即时 / 延迟 match | 立即 match（无视对方意愿） |
| 通知对方 | 仅互划时通知 | 立即通知"X 用 Super Hi 喜欢了你" |
| 对方已删除 / 注销 | 跳过 match 触发 | 抛 TARGET_USER_NOT_FOUND |
| 落 like_record | 仅 RIGHT 单向 | 不落（立即 match 没有暗恋窗口） |

**SuperHi 的特殊路径**：
- **配额扣减**：先扣 cards + right_swipe，再扣 super_hi 赠送配额（按订阅档位判断 0 / 0 / 1 / 1）；
- **赠送用完后走金币**：100 金币一次，调 `payment-service.consumeCoins(idempotent_key="super_hi:<user>:<target>")`；
- **金币 RPC 失败 → 回滚已扣配额 + 抛 SUPER_HI_INSUFFICIENT**（保证配额不被吞）。

**追问**：

- Q: 金币幂等键 `"super_hi:<user>:<target>"` 有什么用？
  A: payment-service 用 idempotent_key 去重。跨进程 / 跨服务重试时，即使 RPC 超时重发，payment 端也能识别"已扣过"。

- Q: SuperHi 对 DH 为什么不延迟？
  A: 用户付费（订阅赠送 / 100 金币）就期望**立即**看到结果。如果延迟被防穿帮覆盖，付费用户的 UX 反而受损。

- Q: SuperHi 和 match.source = SWIPE_SUPER_HI 是什么关系？
  A: SuperHi 接口在 match 表 source 字段写入 `SWIPE_SUPER_HI`，与 RIGHT 互划的 `SWIPE_MATCH` 区分。

---

## 场景问题

### Q13：让你设计一个 dating app 首页 Feed，你会怎么设计？

**考察点**：系统设计能力（推荐 + 互动）

**回答框架**：

1. **需求澄清**：
   - 用户量级：100k DAU ~ 10M DAU？
   - 每用户每天拉多少次 feed？
   - 匹配延迟容忍度（即时 vs 延迟）？
   - BH/DH 比例？

2. **数据模型设计**：
   - swipe_history（划卡历史）；
   - match（匹配关系）；
   - like_record（暗恋）；
   - visit_record（访问）；
   - preference_profile（偏好画像）；
   - Redis feed LIST（推荐队列）。

3. **核心接口设计**：
   - `GetTodayFeed(user_id, count)`；
   - `Swipe(user_id, target_id, direction)`；
   - `SuperHi(user_id, target_id)`；
   - `ListMatches(user_id, page)`；
   - `ListLikesOfMe(user_id, page)`。

4. **技术选型**：
   - 队列：Redis LIST + LPOP 即消费（高频读 + 追加 + 部分覆盖）；
   - 匹配：match 表 UNIQUE 索引 + ON CONFLICT IGNORE；
   - 跨服务：gRPC（避免 HTTP）；
   - 异步：Outbox 模式（本地事务 + 重试）；
   - 调度：Spring `@Scheduled` + Redisson 锁（多实例分布式）。

5. **权衡取舍**：
   - 队列容量 vs 消费配额：240 vs 50/80/120（留余量）；
   - BH 严格 vs DH 渐进：质量优先 vs 凑数优先；
   - 即时 match vs 延迟 match：用户体验 vs 防穿帮。

### Q14：怎么防止 match 重复触发？

**回答框架**：
1. 召回阶段过滤 `user_swipe_history`（**根本解决**）；
2. swipe 接口幂等：UNIQUE(user_id, target_user_id)；
3. match 创建兜底：UNIQUE(user_id_low, user_id_high) + INSERT IGNORE + ERROR 日志报警。

### Q15：用户反馈"今天看不到新人"，可能是什么原因？

**排查思路**：
1. 用户配额是否耗尽？`GetQuota` 看 `dailyCardUsed vs dailyCardLimit`；
2. D1 cron 是否今天跑成功？`match.d1.queue_size.generated_users` 监控；
3. D1 BH 池不足？`match.d1.bh_short_ratio` 监控（持续 > 30% 是报警信号）；
4. swiped SET 是否太大？swipe 太多的老用户看过的 target 也会多；
5. 召回半径是否够大？`match.d1.bh.radius_km` 配置（默认 200km）。

---

## 常见坑及回答

### 坑 1：D1 cron 失败后用户列表一直消费旧队列

- **场景**：某天 03:00 Redis 抖动，D1 cron 写 LIST 失败。
- **现状**：Redis LIST 不被覆盖 → 用户继续消费旧 LIST → 旧 LIST 耗尽 → GetTodayFeed 触发 D0 实时重建。
- **回答**：D1 失败是"静默失败 + 兜底"路径，对 App 透明；新用户首次拉 feed 走 D0 实时重建，体验稍弱但不致命。**运营需要监控 `match.d1.generated_users` 长期无数据**。

### 坑 2：match 表 UNIQUE 触发后被当作 bug

- **场景**：用户 A 在两台设备同时 swipe B 都 RIGHT → 互划 → 两个线程都尝试 createMatch(A, B)。
- **现状**：第一个线程 INSERT 成功；第二个线程触发 UNIQUE → 打 ERROR 日志 + 返回 existing match。
- **回答**：UNIQUE 触发不抛异常 → 用户 UX 正常；ERROR 日志带足够上下文（pair / existing_source / new_source）→ 运营收到报警说明召回过滤有 bug，需要排查。

### 坑 3：DH 计划 1 分钟内塞 30 个 like 穿帮

- **场景**：运营误调 `match.dh_plan.online_count_max = 30`。
- **现状**：单次生成 30 个 DH → 30 个 like_record 同时插入 → 用户在 1 分钟内看到 30 条新 like。
- **回答**：execute_time 在 [now, now+30min] 均匀分布 — 即使一次性生成 30 个，**execute 时也是 30 分钟内陆续触发**。但文案池 + cooldown 仍不能完全避免用户意识；24h like 上限 15 是兜底。

### 坑 4：Redis AOF 宕机丢 1s 配额写入

- **场景**：Redis 进程异常退出，AOF `appendfsync everysec` 配置，最坏丢 1s 写入。
- **现状**：用户至多多刷几张卡，**不做 PG 强校验**。
- **回答**：接受这个 trade-off — 配额丢失影响小，**不值得为它维护 PG 强校验 + EOD reconcile 链路**。AOF 重启后从磁盘恢复，最多 1s 数据丢失。

### 坑 5：进程内 TaskScheduler 重启丢延迟 match 任务

- **场景**：服务 03:30 重启，丢了一些 03:00 ~ 03:30 之间 schedule 的延迟 match 任务。
- **现状**：用户感知是"她没喜欢我"，符合 dating app 常见预期。
- **回答**：trade-off 已在 PRD 明确接受。如未来需要可加 Outbox 风格持久化兜底。

---

## 加分项（可主动提及）

1. **CLAUDE.md 红线落地**：match-service 严守"只用 PostgreSQL / Redis 7 / gRPC / MyBatis-Plus"；不直连其他服务的 PG / Redis；key 前缀 `putao:match:`；时区统一 UTC。
2. **完整的可观测性设计**：业务监控（match.feed.cards_returned, match.quota.exceeded, match.outbox.pending 等 10+ 指标）+ 业务日志（Duplicate match attempt ERROR 等关键信号）。
3. **配置驱动**：所有阈值、权重、bonus、窗口、上下限都走 Nacos，运营可热刷（match.score.*, match.dh_plan.*, match.cold_start.*, match.d1.* 等）。
4. **降级容错**：user-service 不可用时返回空集合；payment-service 不可用时按 FREE 处理；im-service 不可用时 Outbox 兜底；**主流程 RPC 永不阻塞 UX**。