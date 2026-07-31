# IM Service 特殊场景设计

> 本文只讨论 IM Service 中具有架构价值的横切场景。用户能做什么见《IM Service 业务清单》，逐方法代码链路见《IM Service 业务流程详解》。

## 一、场景总览

| 场景 | 核心冲突 | 当前方案 | 主要限制 |
|---|---|---|---|
| Provider 回调归一化 | 外部 JSON 多变，内部规则需要稳定模型 | 适配器 + sealed `ImEvent` + fail-open | OpenIM 类未实现接口，入口也缺失 |
| 发送前安检与扣费 | 安全/资金规则与发消息延迟冲突 | 同步预检 + 可选异步扣费 | DH 豁免失效、无可靠任务、deadline 未生效 |
| 消息镜像与 AI 路由 | 外部投递已完成，本地副作用可能失败 | after-send 回调后落库再路由 | 必填列缺失、AI 占位、回调线程阻塞 |
| 在线状态双层存储 | 实时范围读与历史查询需求不同 | Redis ZSet + PostgreSQL 会话表 | 双写非原子、多端语义不完整 |
| 时间窗消费 | match-service 需要增量拉取 | 时间窗口 + limit + Redis 游标 | 闭开区间不一致、截断后推进游标 |
| 孤儿会话清扫 | 离线回调可能丢失，多实例不能重复跑 | ShedLock + PG 扫描 + ZREM | 旧会话可能删除新在线态 |
| 外部 token 签发 | 外部引擎不可用时如何对用户表达失败 | 空值降级 | 空成功难以区分、通话房间不对称 |

## 二、Provider 回调归一化与 fail-open

### 2.1 业务背景与约束

OpenIM 回调携带供应商特有的事件名和嵌套 JSON。反导流、扣费、落库和在线状态不应直接依赖这些字段，否则供应商升级会扩散到全部业务处理器。

约束：

- before-send 的响应影响消息能否发送，不能随意抛异常。
- 未知事件既可能是无关回调，也可能代表协议漂移。
- 多 Provider 扩展价值依赖真正的接口装配，而不只是存在同名方法。

### 2.2 候选方案

| 方案 | 优点 | 缺点 |
|---|---|---|
| handler 直接解析 OpenIM JSON | 最少代码 | 供应商字段扩散，难测试、难切换 |
| 按事件类型建立多个 webhook | 路由清晰 | gateway 与 provider 强耦合 |
| 统一原始入口 + Provider 适配器 | 内部模型稳定，扩展成本低 | 必须保证注册、错误语义和观测完整 |

### 2.3 当前实现

设计链路：

```text
OnRawCallback
  -> CallbackService.handleRawCallback
  -> ImProviderAdaptorManager.parse
  -> ImEvent
  -> switch 分发到 BeforeSend / MessageSent / Presence
```

`ImEvent` 是 sealed interface，包含发送前、发送后、上线、下线和未知五类事件。`CallbackService` 的 pattern switch 因此能在编译期检查事件分支是否穷尽。

未知 Provider、解析异常或未知 callback type 都转换成 `UnknownEvent`，最终返回 0。这是 fail-open：优先避免因为业务侧解析故障阻断 OpenIM。

### 2.4 实现与设计的偏差

当前 `OpenImAdaptor` 标了 `@Component`，但类声明没有 `implements ImProviderAdaptor`。因此 Spring 注入的 `List<ImProviderAdaptor>` 不包含它，manager 会直接返回 unsupported。仓库内也没有找到 gateway 接收 OpenIM webhook 并调用 IM Service 的入口。

这意味着“适配器抽象已写出”与“回调能力已生效”是两件事。当前应标为部分实现且链路不可达。

### 2.5 一致性、失败和演进

- fail-open 保护可用性，但会在未知事件时绕过反导流、扣费和落库。
- 当前只有日志，没有 unknown 比例指标、原始 payload 安全采样或死信。
- 生产演进应先补入口、接口实现和契约测试；再为不同事件定义 fail-open/fail-close 策略，而不是一律返回成功。
- before-send 至少需要严格的延迟预算和报警；after-send 可引入持久化 inbox 或消息队列重放。

## 三、发送前安检与异步扣费

### 3.1 业务背景与数据特点

聊天发送前需要执行两类规则：

1. 安全规则：文本不能包含站外联系方式。
2. 资金规则：真人发送消息需要消耗金币，数字人回复不扣。

这些规则位于用户发消息的关键路径，延迟和可用性直接影响聊天体验；但扣费又要求幂等与可追踪。

### 3.2 当前决策顺序

```java
if (fromUserId == null) return OK;
if (isDigitalHuman(fromUserId)) return OK;
if (antiFunnelEnabled && msgType == 1 && detector.detect(content) != null) {
    return REJECT_CONTACT_INFO;
}
if (chargeEnabled) return checkAndCharge(...);
return OK;
```

异步模式：

```text
getBalance
  -> null: 放行
  -> balance < cost: 拒绝
  -> balance >= cost: 投递进程内线程池，立即放行
```

同步模式直接调用 `consumeCoins`，根据 OK、INSUFFICIENT、FAILED 决定放行或拒绝。

### 3.3 幂等边界

IM Service 生成 `im-msg:<messageId>` 作为 payment-service 的幂等键。这能否真正去重，最终由 payment-service 的事务和唯一约束决定。

当前本地没有扣费任务表：

- OpenIM 重复回调会重复投递，但下游幂等键可阻止重复扣减。
- 进程在放行后、线程执行前崩溃，扣费任务永久丢失。
- RPC 结果不确定时不重试；即便未来重试，也必须复用同一幂等键。
- messageId 为空时，所有空 ID 消息共享 `im-msg:`，可能把多条消息错误地视为同一幂等操作。

### 3.4 并发与容量

`CoinChargeDispatcher` 使用 `Executors.newFixedThreadPool(2)`。该工厂背后是无界队列：

- 高峰期任务可无限积压并占用内存。
- 没有拒绝策略、队列深度指标或优雅关闭。
- `BeforeSendHandler` 自身还创建了另一个未使用的 2 线程 executor。

`PaymentServiceClient` 声明了 800ms/2000ms 常量，但没有对 gRPC stub 调用 `withDeadlineAfter`，所以文档不能声称 deadline 已生效。

### 3.5 降级语义

- sender 解析失败：放行。
- 用户类型服务：发送前根本没有调用，`isDigitalHuman` 固定 false；DH 会被当 BH。
- 余额查询失败：异步模式放行。
- 异步扣费失败：只记日志，消息不撤回。
- 同步扣费失败：拒绝。

这是体验优先的 fail-open 方案，但资金漏扣上界并不是“最多单条 6 金币”：持续故障期间每条消息都可能漏扣。没有数据支持人民币换算、成功率或实际损失，不应写入项目事实。

### 3.6 演进方向

P0：

- 接入真实用户类型判断。
- 校验非空且稳定的 messageId。
- 给 gRPC 设置 deadline。
- 将“放行后扣费”写入本地 outbox，再由 worker 投递。

P1：

- 有界线程池、队列指标、重试分类和死信处理。
- 把联系方式规则做成可版本化策略，并补混淆、Unicode、图片 OCR 等能力。

## 四、消息镜像、路由与 AI 回复

### 4.1 业务冲突

OpenIM after-send 表示消息已对用户可见。此后本地落库、用户类型查询或 AI 生成失败，都不能再原子回滚外部消息。因此该链路天然是“外部事实已发生，本地副作用最终一致”。

### 4.2 当前链路

候选方案的取舍：

| 方案 | 优点 | 缺点 |
|---|---|---|
| 回调线程同步完成落库与 AI | 代码直观 | 延迟高，任一依赖故障拖住回调 |
| 回调只记 inbox，worker 执行副作用 | 可重放、可观测 | 多一张任务表和状态机 |
| 直接依赖 OpenIM 重试 | 少建基础设施 | 当前成功响应会吞掉本地失败，且无法控制重放粒度 |

```text
MessageSentEvent
  -> MessageSentHandler.saveMessage
  -> UserServiceClient.isDigitalHuman(from/to)
  -> MessageManager.determineRouteType
  -> BH_DH ? AiReplyService.triggerAiReply : 结束
```

路由矩阵：

| from | to | route |
|---|---|---|
| BH | BH | `BH_BH` |
| BH | DH | `BH_DH` |
| DH | BH | `DH_BH` |
| DH | DH | `DH_DH` |

### 4.3 当前正确性缺口

保存消息发生在路由计算之前，实体没有设置 `routeType`。迁移却规定：

```sql
route_type VARCHAR(16) NOT NULL
```

MyBatis-Plus 默认不会给这个字段产生有效值，因此插入失败。异常被 `saveMessage` 的广义 catch 吞掉，之后仍会继续路由。

即使修正字段，当前也不是可靠 inbox：

- plain INSERT，没有显式 upsert。
- 唯一冲突和数据库故障都被同样处理。
- 没有回调接收记录、重试状态或补数入口。

### 4.4 AI 现状

`AiReplyService` 没有 ai-chat client。它同步 `Thread.sleep(2000)`，随后用 `NotificationService` 发送固定英文占位内容。

影响：

- after-send 回调至少阻塞约 2 秒。
- 没有基于原消息生成回复。
- `im.ai-reply.*` 与 `im.typing.*` 配置全部未使用。
- 发送的是 `msgType=100` 自定义消息，不是经普通文本发送能力。
- 通知 key 没进入 JSON，客户端路由语义不完整。

### 4.5 演进方向

推荐拆为持久化状态机：

```text
RECEIVED -> MESSAGE_RECORDED -> AI_REQUESTED -> REPLY_READY
         -> SEGMENT_SENDING -> COMPLETED / RETRY / DEAD
```

- after-send 入口先以 messageId 写 inbox，唯一约束去重。
- 同一数据库事务写消息镜像和 AI 任务。
- worker 调 ai-chat 和 OpenIM，按稳定的派生 messageId 幂等发送。
- typing 是体验信号，失败可降级；普通回复的成功/失败必须可追踪。

## 五、Redis ZSet 与 PostgreSQL 在线会话双写

### 5.1 为什么使用两种存储

业务有两种访问模式：

- 当前在线集合按上线时间做范围查询，ZSet 适合 `rangeByScore`。
- 最近离线与会话时长需要历史记录，PostgreSQL 更适合审计与分析。

双层本身合理，但当前实现不是“Redis 缓存 + PG 权威源”的简单关系：下线逻辑依赖 Redis 中的上线时间，Redis 丢失会阻止 PG 关闭，所以两者共同决定结果。

| 方案 | 适用点 | 代价 |
|---|---|---|
| 只用 Redis | 当前在线与范围读简单 | 缺历史，丢失后难审计 |
| 只用 PG | 单一持久化事实 | 高频时间窗读取和在线集合维护压力更大 |
| Redis + PG 双写 | 同时满足实时与历史 | 必须设计双写失败、补偿和多端语义 |

### 5.2 上线流程与竞态

`markOnline` 先 `addIfAbsent`，然后再读 score，通过 `score == onlineAt` 推断是否首次加入。

问题：

- 没使用 `addIfAbsent` 的 Boolean 返回值。
- 重复事件携带相同时间戳时，score 仍等于 onlineAt，可能重复插 PG。
- Redis 成功后 PG 插入失败，事务只回滚 PG；ZSet member 留存，后续不同时间戳上线被判定为“已在线”，无法补 PG。

更稳妥的实现应直接使用 ZADD NX 返回值，并通过数据库唯一约束或事件 ID 保证重复上线不会开多条会话。

### 5.3 下线流程与多端问题

下线先读 ZSet score，再查最近一条 `offline_at IS NULL` 会话并更新，最后 ZREM。

问题：

- 任意一个端下线就移除整个用户，无法表达另一端仍在线。
- `platform` 参数没有用于选择会话。
- Redis 丢失时既不关 PG，也不创建补偿记录。
- offlineAt 小于 onlineAt 时会写负 duration。
- PG 提交失败不能恢复已经执行的 ZREM。

多端场景应选择：

- 每设备/平台 member；或
- 用户级在线引用计数 + 设备会话表；或
- 由 OpenIM 提供聚合后的“最后一端离线”事件契约。

### 5.4 查询窗口与游标

proto 写的是 `[since, until)`，实际 Redis 和 SQL 都是闭区间。消费方又把 `until` 保存为下一次 `since`：

- 闭区间会重复端点。
- 改成开区间可避免重复，但仍需稳定排序和 tie-breaker。
- 只有时间戳游标无法安全分页同一毫秒的多条记录。

更可靠的游标应包含 `(event_time, unique_id)`，服务端返回 `nextCursor` 与 `hasMore`。消费方只有在当前页成功持久化后才能推进。

## 六、ShedLock 孤儿会话清扫

### 6.1 当前方案

`PresenceSweepJob` 每 30 分钟触发，使用：

```java
@SchedulerLock(name = "presenceSweep", lockAtMostFor = "PT5M")
```

`ShedLockConfig` 使用 JDBC 和数据库时间。任务查询超过 26 小时仍未离线的 PG 会话，逐条设置：

- `offline_at = online_at + 26h`
- `duration_seconds = 26 * 3600`
- Redis ZREM userId

可选方案：

| 方案 | 优点 | 缺点 |
|---|---|---|
| 单实例 `@Scheduled` | 最简单 | 扩容后重复执行 |
| Redis 分布式锁 | 获取快、已有 Redis | 锁与待修复的 PG 状态分属两套系统 |
| JDBC ShedLock | 任务级注解清晰、使用 DB 时间 | 租期到期后仍需任务自身幂等 |

### 6.2 优点与限制

优点：

- 多实例只有一个执行者。
- DB 时间减少应用节点时钟漂移影响。
- 任务异常被捕获，不会终止后续调度。

限制：

- `lockAtMostFor` 不是执行超时。任务超过 5 分钟后，另一个实例可再次获得锁并并发处理。
- 查询没有 limit，一次事务可能加载全部孤儿。
- 没有 `lockAtLeastFor`，快速完成后会立即释放。
- 旧 PG 孤儿与新的 Redis 在线 member 使用同一 userId；清扫旧记录可能误删新会话。
- 每条 update 与 Redis 删除之间没有跨存储原子性。

### 6.3 演进方向

- 按主键分页或 `SKIP LOCKED` 分批处理。
- 更新时增加 `offline_at IS NULL` 条件并检查 affected rows。
- ZREM 前比较当前 Redis score 是否仍等于待清扫会话的 onlineAt。
- 把 `lockAtMostFor` 设为最坏批次时长以上，并给任务增加批次、耗时、失败指标。

## 七、外部 token 的失败和安全边界

### 7.1 OpenIM token

当前 OpenIM 客户端直接 new `RestTemplate`，没有连接/读取超时、重试器或熔断。失败转成空 token，不区分：

- 用户未注册；
- OpenIM 超时；
- admin secret 错误；
- 响应字段变化。

所谓懒注册分支依赖拿到 HTTP 500 response，但默认错误处理器通常先抛异常；若未来关闭错误处理器，递归调用又没有明确次数参数。

| 失败表达 | 优点 | 缺点 |
|---|---|---|
| 返回空 token | 调用链不抛异常 | 上层容易把失败当成功 |
| 返回明确 gRPC 状态 | 失败可观测、可重试分类 | App 必须处理错误态 |
| 缓存 token 后降级 | 可降低 OpenIM 压力 | 需要处理过期、撤销和用户维度安全 |

### 7.2 LiveKit token

当前使用 HMAC key 本地签 JWT，30 分钟过期。存在三个需要验证的点：

1. 房间名不做用户对排序，两端独立申请会得到不同房间。
2. 普通用户被授予 roomAdmin/roomCreate。
3. 手写 claims 没有 LiveKit SDK 或集成测试证明兼容。

此外，当前 gRPC 源码和 proto 的 peerId 类型不一致，属于更前置的编译阻断。

### 7.3 推荐原则

- 配置缺失或外部服务错误应返回明确错误，不要伪装成成功空值。
- 对称用户对使用稳定房间 ID，最好再加入 call/session ID，避免所有历史通话复用同房间。
- 令牌只授予加入、发布、订阅所需最小权限。
- 外部 HTTP 客户端统一配置 timeout、错误映射和观测。

## 八、设计原则总结

1. **先证明链路可达，再讨论抽象优雅。**
2. **外部事实已发生的回调副作用，需要 inbox/outbox，而不是只 catch 日志。**
3. **跨 Redis/PG 的 `@Transactional` 不是分布式事务。**
4. **时间窗接口必须同时设计边界、排序、limit、游标和失败推进规则。**
5. **幂等键只解决重复，不解决任务丢失。**
6. **配置项不等于能力已实现，依赖存在也不等于代码正在使用。**
