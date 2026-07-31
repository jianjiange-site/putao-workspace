# IM Service 面试考点

> 回答原则：先说业务和数据特点，再说当前代码，最后说一致性边界与演进方案。不要把设计稿、配置项或 TODO 说成已上线能力。

## 1. 如何介绍 IM Service

### 1.1 30 秒回答

IM Service 的目标是把 OpenIM、通话 token、聊天前后置规则和在线状态收口。代码中比较有价值的设计包括 Provider 回调归一化、发送前反导流与幂等扣费键、Redis ZSet 加 PostgreSQL 会话历史，以及 ShedLock 孤儿清扫。不过当前实现仍是骨架阶段：9 个 proto 方法只有 6 个被服务端覆盖，普通发送和 AI 回复是占位，回调入口与适配器装配未闭环，消息落库还有必填字段缺失。我会把这些现状和理想演进明确分开讲。

### 1.2 两分钟回答框架

1. 业务：App 用 OpenIM 聊天、LiveKit 通话；match-service 需要在线信号和配对后的 IM 副作用。
2. 边界：IM Service 调 user/payment gRPC 和 OpenIM HTTP；App 长连接不经过本服务。
3. 关键设计：
   - 原始回调转换为 sealed `ImEvent`。
   - before-send 反导流并支持同步/异步扣费。
   - after-send 保存消息镜像并按 BH/DH 路由。
   - ZSet 保存当前在线，PG 保存会话历史。
   - ShedLock 保证多实例只调度一次清扫。
4. 当前事实：
   - OpenIM token 的 2xx 路径存在。
   - 在线/离线查询与清扫有实现。
   - 三个配对副作用没有服务端实现。
   - callback gateway 入口未找到，`OpenImAdaptor` 未实现接口。
   - AI 与消息发送仍是占位。
5. 优先改进：先修编译和链路可达性，再补 inbox/outbox、分页游标和观测。

## 2. Provider 适配器与回调

### 2.1 为什么需要 Provider 适配器

核心回答：

- 外部 IM 引擎的事件名、字段层级和错误码会变化。
- 业务规则需要稳定的内部事件，不应到处读 OpenIM JSON。
- `ImProviderAdaptor` 负责协议转换，`ImEvent` 负责内部语义，`CallbackService` 只分发。
- sealed interface 的价值是新增事件类型时，pattern switch 能在编译期提示漏处理。

追问：当前真的支持多 Provider 吗？

不能说支持。只有 OpenIM 解析类，而且它没有 `implements ImProviderAdaptor`，因此 manager 的接口列表不会包含它；也没有第二个 Provider 或契约测试。当前是“抽象意图存在，装配不成立”。

### 2.2 为什么 UnknownEvent 返回成功

当前选择是 fail-open：

- 好处：字段漂移或无关事件不会阻塞 OpenIM。
- 代价：before-send 未识别时会绕过反导流和扣费，after-send 未识别时会漏落库。
- 当前只有日志，缺少 unknown 比率、payload 采样和报警。

更完整的回答是：不同事件应有不同失败策略。体验信号可以 fail-open；资金或安全规则至少要有严格超时预算、告警和可追溯记录。

### 2.3 回调链路有哪些真实性问题

- 仓库没有找到 mobile-gateway 的 OpenIM webhook Controller 或 `onRawCallback` client 调用。
- gRPC 入口没有签名、来源鉴权、payload 限制。
- OpenIM 适配器没有实现接口。
- unknown 最终都返回 0。

因此不能说“所有消息都经过 IM Service 钩子”。

## 3. 发送前反导流与扣费

### 3.1 为什么放在服务端钩子

业务特点：

- App 可被修改，客户端校验不能作为安全边界。
- 多端发送需要统一收费规则。
- OpenIM 是通用管道，不应该承载 dating 业务。

所以合理位置是 OpenIM 投递前回调业务服务。但这个结论成立的前提是 webhook 配置、鉴权、超时和返回码映射真正闭环；当前仓库尚未证明。

### 3.2 当前决策顺序是什么

1. 发送方解析失败：放行。
2. 数字人：设计上放行；当前方法固定 false，实际不生效。
3. 文本命中联系方式：拒绝 5002。
4. 扣费关闭：放行。
5. 异步模式余额不足：拒绝 5003；余额查询失败：放行；余额足够：投递异步扣费并放行。
6. 同步模式扣费失败：拒绝 5004。

### 3.3 异步扣费如何保证幂等

项目事实：

```text
idempotent_key = im-msg:<messageId>
```

同一 messageId 重复调用 payment-service 时，下游可按幂等键去重。

必须补充的边界：

- 幂等只防重复执行，不保证任务一定执行。
- 任务只在进程内线程池；服务放行后宕机会漏扣。
- 没有重试、任务表和死信。
- messageId 为空会退化成相同的 `im-msg:`。
- payment client 声明的 800/2000ms 常量没有用到 stub，不能说已配置 deadline。

### 3.4 为什么“最多损失一条 6 金币”不严谨

持续 payment 故障时，每条消息的余额查询都可能失败并被放行，所以漏扣可以连续发生。6 只是单条默认 cost，不是故障总损失上界。没有生产数据也不能换算成人民币或声称成功率。

### 3.5 如何演进成可靠扣费

这是演进方案，不是当前实现：

1. before-send 校验并在本地事务写 charge outbox。
2. 只有 outbox 写成功才决定是否放行，或根据业务选择有限 fail-open。
3. worker 以稳定 messageId 调 payment。
4. 按 gRPC status 分类重试；余额不足不重试。
5. 记录 DONE/RETRY/DEAD，提供对账。
6. 有界线程池只作为执行器，不作为可靠队列。

## 4. 消息落库与 AI 路由

### 4.1 after-send 的一致性本质

OpenIM 已经把消息发出，本地落库和 AI 都是后置副作用。它们失败不能回滚外部消息。因此：

- 不能靠一个本地 `@Transactional` 获得全链路原子性。
- 应使用 inbox 记录回调，之后幂等执行副作用。
- 当前实现只是同步调用和 catch 日志，不是 at-least-once。

### 4.2 当前为什么写不进 `chat_messages`

`MessageSentHandler.saveMessage` 没有设置 `routeType`，而表定义 `route_type NOT NULL`。路由计算发生在插入尝试之后，而且没有回写 entity。异常被吞掉后仍继续 AI 路由。

面试时可以把它当成代码审查点：

- 写入前检查所有数据库必填字段。
- route 应先计算再组装实体。
- 唯一冲突要与数据库不可用区分。
- 回调成功响应不能在关键副作用完全不可追踪时无条件返回。

### 4.3 `message_id UNIQUE` 是否等于 exactly-once

不是。

- 唯一约束只阻止重复行。
- 首次 INSERT 失败且回调不重试时仍会丢。
- 当前 after-send 异常被吞并返回 0，无法证明至少一次。
- 更准确的说法是“唯一约束提供幂等写入基础”，不是“逻辑 exactly-once”。

### 4.4 BH/DH 路由如何工作

`UserServiceClient`：

1. 读 Redis `putao:im:user:type:<id>`。
2. 命中 `1` 为 DH，否则 BH。
3. 未命中调 user-service。
4. 成功写 10 分钟 TTL。
5. 失败返回 BH，不缓存失败。

再由两个 boolean 计算 `BH_BH/BH_DH/DH_BH/DH_DH`。只 BH_DH 触发 AI。

风险：

- user-service 故障会把 DH 当 BH。
- from/to 分别调用，没有批量。
- 当前 route 没写进消息实体。

### 4.5 AI 回复当前做了什么

事实只有：

- 同步 sleep 2 秒。
- 固定英文回复。
- 用 OpenIM custom msg 发送。
- 没有 ai-chat gRPC、typing 循环、分段、图片理解或异步 executor。

`application.yml` 中的 AI/typing 参数是“配置存在但未使用”，不能拿来讲已实现拟真回复。

### 4.6 如何设计可恢复的 AI 回复

演进方案：

- inbox 以原消息 ID 去重。
- 同一事务写消息镜像和 AI task。
- worker 状态：PENDING → GENERATING → SENDING → DONE/RETRY/DEAD。
- 派生消息 ID 使用 `<origin>_ai_<segment>`。
- typing 是可丢体验信号，回复消息必须记录 OpenIM serverMsgId。
- 避免在 callback 线程 sleep。

## 5. 在线状态双层存储

### 5.1 为什么使用 ZSet + PG

从数据访问模式回答：

- ZSet 的 score 是上线时间，适合查询“某时间窗内仍在线且新上线的用户”。
- PG 会话表保留离线时间和时长，适合历史、离线窗口与审计。
- 两者不是简单强一致事务，必须明确失败边界。

不要声称“Redis 比 PG 快 10 倍”，项目没有基准测试。

### 5.2 上线为什么用 ZADD NX

意图是同一用户已在线时不覆盖首次上线时间，从而：

- 当前在线集合只有一个 member。
- 时间窗扫描不会因重复 online 不断把 score 推到当前。

当前实现缺陷：

- 忽略 ZADD NX 的返回值，又读 score 与传入时间比较。
- 相同时间戳重复回调可能被当成首次上线。
- 没有 PG 唯一约束阻止多个打开会话。

### 5.3 `@Transactional` 能保证 Redis 和 PG 一致吗

不能。

- Spring 事务只管理 datasource。
- 上线时 Redis 成功、PG 失败会留下孤儿 member。
- 下线时 PG 更新后 ZREM，最终 PG 回滚也不会恢复 Redis。
- 需要幂等事件、补偿、outbox 或以单一权威源重建。

### 5.4 多端登录有什么问题

ZSet member 只有 userId，没有设备/平台维度。任一端离线都会 ZREM 用户；`platform` 也没用于关闭具体会话。

可选演进：

- member 使用 `userId:deviceId`，查询时聚合用户；
- Redis Hash/Set 记录设备集合，最后一个设备离线才清用户级 ZSet；
- 由 OpenIM 提供明确的聚合在线事件。

### 5.5 online/offline 查询为何会漏数据

服务端问题：

- 契约写 `[since, until)`，实现是双闭区间。
- limit 没有最大值夹紧。
- online 无 nextCursor；offline 按 userId 排序、limit 后 distinct。

消费方问题：

- RPC 异常被转空列表。
- 无论失败、空结果或截断都把游标推进到 until。

高峰超过 5000 或服务短暂失败时，未消费部分可能永久跳过。

### 5.6 正确的增量接口怎么设计

演进方案：

- 稳定排序 `(event_time, row_id)`。
- opaque nextCursor 包含两个值。
- response 返回 `items/nextCursor/hasMore`。
- limit 服务端夹紧。
- 消费方处理成功后才持久化 cursor，循环直到 hasMore=false。
- 明确上界开区间，避免端点重复。

## 6. ShedLock 与孤儿会话清扫

### 6.1 为什么用 ShedLock

定时清扫是任务级互斥，已有 PostgreSQL，因此 JDBC ShedLock 是合理的低成本方案。`usingDbTime()` 避免依赖各应用节点时钟。

### 6.2 `lockAtMostFor` 解决什么

它是锁的最长租期，不是任务超时：

- 实例宕机后，锁最终可释放。
- 如果任务正常运行超过 5 分钟，锁也会过期，另一个实例可能同时进入。

所以租期必须覆盖最坏批次时长，任务本身还要幂等和条件更新。

### 6.3 当前清扫最大的竞态

同一 userId：

1. PG 中有 26 小时前的旧孤儿会话。
2. 用户后来重新上线，Redis score 是新时间。
3. sweep 关闭旧 PG 行。
4. 无条件 `ZREM userId`，把新在线态也删掉。

修复方式是 ZREM 前比较 Redis score 与旧 session.onlineAt，或把 Redis member 精确到 session/device。

### 6.4 当前批处理问题

- 一次读取全部孤儿。
- 一个大事务逐条 update。
- 没有 limit、分页和 affected-row 条件。
- Redis 删除不能随 PG 回滚。

演进可用小批次、`SKIP LOCKED`、条件更新与重建任务。

## 7. OpenIM token 与懒注册

### 7.1 正常链路

gateway 取认证 userId → IM gRPC → OpenIM `/auth/get_user_token` → 返回 token 和有效期。

### 7.2 为什么当前懒注册并不可靠

- `RestTemplate` 默认把 500 变成异常，代码看不到 `response.getStatusCode()==500`。
- catch 后直接返回 empty，没有注册。
- 如果改错误处理器让 500 可见，递归又没有显式一次限制。
- 注册时“already exists”非 2xx 分支也可能被同样的错误处理器截断。

面试中应说“代码意图是懒注册，但当前错误处理使分支无法可靠到达”。

### 7.3 外部 HTTP 客户端还缺什么

- connect/read timeout；
- 明确错误码映射；
- admin token 缓存与失效刷新；
- 有界重试；
- 请求/响应契约测试；
- 避免把失败伪装成成功空 token。

## 8. LiveKit token

### 8.1 当前实现要点

- HS 密钥本地签名。
- 30 分钟 TTL。
- room 名是 `call_<userId>_<peerId>`。
- 包含 join/publish/subscribe 以及 admin/create 权限。

### 8.2 三个关键问题

1. 当前 proto `peer_id` 是 long，gRPC 代码用 String 接收，源码不匹配。
2. 房间名有方向：A→B 与 B→A 不同。
3. gateway 通话入口返回空对象，没有调用 IM Service。

此外应使用官方 SDK 或集成测试验证 claims，并应用最小权限。

### 8.3 如何设计稳定房间

演进思路：

```text
room = call:<callSessionId>
participants = {callerId, calleeId}
```

如果没有 call session，至少将用户对排序。但排序会让两人所有通话复用一个房间，仍需要 nonce/callId 区分会话。

## 9. gRPC 契约与实现状态

### 9.1 proto 声明了什么

共 9 个方法：

- 普通消息 1
- 系统消息 1
- 会话/开场白 2
- token 2
- 原始回调 1
- 在线查询 2

### 9.2 服务端实际覆盖

覆盖 6 个：

- sendMessage（占位）
- getImToken
- generateCallToken（源码类型冲突）
- onRawCallback
- listOnlineUsers
- listRecentOfflineUsers

完全没 override 3 个：

- sendSystemMessage
- ensureConversation
- triggerDhOpening

未覆盖方法会返回 gRPC UNIMPLEMENTED，而不是业务空结果。

### 9.3 为什么这会影响 match-service Outbox

Outbox 只能保证调用失败后重试，不能让不存在的下游能力最终成功。下游长期 UNIMPLEMENTED 时，任务只会持续失败或进入 DEAD。可靠上游不能补齐缺失下游。

## 10. 配置、依赖和真实能力

### 10.1 如何识别“配置了但没用”

需要从读取点反查：

- RocketMQ 有 pom/yml，无 producer/consumer。
- Redisson 有 Bean，无业务引用。
- ai-chat host/port 有配置，无 stub。
- typing/AI 节奏有配置，无 `@Value` 读取者。
- Micrometer registry 有依赖，无业务 meter 注册。

面试时不要把配置表背成已实现功能。

### 10.2 当前测试和构建情况

- `im-service/src/test` 没有测试文件。
- Maven 离线验证因本地缺 `jjwt-impl`、`jjwt-jackson` 失败，未完成编译。
- 静态核对已确认 proto 生成代码的 `getPeerId()` 返回 long，而服务代码赋给 String。

因此不能声称“测试覆盖完整”或“当前构建通过”。

## 11. 故障场景推演

### 11.1 Redis ZADD 成功后 PG 插入失败

结果：Redis 认为用户在线，PG 没有会话；下一次不同时间戳 online 因 NX 不再补 PG。需要对账/重建或事件 inbox。

### 11.2 PG 关闭会话后 ZREM，最终事务回滚

结果：PG 会话仍打开，Redis 已离线。sweep 以后可能关闭 PG，但短期两个视图不一致。

### 11.3 before-send 已放行，进程在异步扣费前宕机

结果：消息发送、金币未扣，没有任务可恢复。幂等键无能为力，因为任务从未调用下游。

### 11.4 after-send 写库失败

结果：异常被吞，回调返回 0，消息镜像丢失；若 BH_DH，AI 占位仍可能继续发送。

### 11.5 match-service 拉在线用户时 IM Service 超时

结果：client 返回空，计划服务仍推进游标，窗口数据跳过。

### 11.6 sweep 超过 5 分钟

结果：锁租期到期，另一实例可能并发执行；没有条件更新会重复处理。

## 12. 代码评审高频问题

### 12.1 看到 `@Transactional` 应检查什么

- 调用是否经过 Spring 代理。
- 事务只覆盖哪些资源。
- 方法中是否混有 Redis/HTTP/MQ。
- catch 是否吞掉异常导致不回滚。
- 大循环是否造成长事务。

### 12.2 看到“幂等”应检查什么

- 幂等键是否非空、稳定、业务域隔离。
- 唯一约束在哪里。
- 同一键不同参数如何处理。
- 重复执行与首次丢失是否被混淆。
- 返回成功是否能重放原结果。

### 12.3 看到时间窗查询应检查什么

- 上下界开闭。
- 稳定排序。
- 同一时间戳 tie-breaker。
- limit 上限。
- hasMore/nextCursor。
- 失败时是否推进游标。

### 12.4 看到定时任务应检查什么

- 多实例互斥。
- 锁租期是否覆盖最坏执行时间。
- 批次上限。
- 幂等/条件更新。
- 失败重试和观测。
- 外部副作用能否随事务回滚。

## 13. 生产改进优先级

### P0：可构建与链路正确性

1. 修复 `peerId` 类型冲突并建立可重复构建。
2. 让 `OpenImAdaptor implements ImProviderAdaptor`。
3. 补 gateway webhook、签名验证和回调契约测试。
4. 修复 `route_type` 计算与落库顺序。
5. 接入真实 DH 判断。
6. 明确 sendMessage 占位行为，或在实现前直接返回 UNIMPLEMENTED。
7. 实现/下线配对副作用三个契约，避免长期 Outbox 失败。

### P1：可靠性和资金安全

1. callback inbox、AI/扣费 outbox。
2. gRPC/HTTP deadline、错误分类和有限重试。
3. 时间窗 cursor/hasMore 与消费方成功后推进。
4. 在线态双写补偿、多端语义和 sweep 条件 ZREM。
5. 有界线程池、优雅关闭、任务指标。

### P2：体验与扩展

1. 真正接 ai-chat，异步状态机、typing 和分段。
2. Provider 契约测试与第二实现验证抽象。
3. LiveKit 官方 token SDK、call session 与最小权限。
4. 反导流规则版本化、Unicode 混淆与多媒体识别。

## 14. 推荐的回答结构

面对任意问题，可以按六步回答：

1. **业务特征**：为什么有这个问题。
2. **数据与约束**：时序、吞吐、资金、安全、历史。
3. **当前实现**：精确到类、方法、SQL、Redis key。
4. **一致性边界**：事务覆盖什么，不覆盖什么。
5. **失败推演**：超时、重复、丢失、进程崩溃会怎样。
6. **演进方案**：明确标为建议，不冒充项目事实。

示例：

> 在线状态既要按上线时间高频范围读，又要保留离线历史，所以当前用 Redis ZSet 和 PG 会话表。但当前 `@Transactional` 只管 PG，上线 Redis 成功而 PG 失败会产生不一致；多端也没有引用计数。短期应直接使用 ZADD NX 返回值、增加会话约束和对账，长期可用设备会话模型与可靠事件驱动双写。
