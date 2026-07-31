# IM Service 业务流程详解

> 本文是代码学习主文档。所有结论以当前工作区源码为准；设计稿只用于发现意图。
>
> 路径基准：`dating-server/im-service`、`proto/im/im.proto`，并核对 mobile-gateway、match-service、user-service 与 payment-service 调用方。

## 一、统一入口与分层

```text
用户 HTTP
  -> mobile-gateway ImController / ImServiceImpl / ImClient
  -> gRPC ImGrpcService
  -> TokenService / CallbackService / PresenceService
  -> manager / client
  -> OpenIM / LiveKit JWT / Redis / PostgreSQL / user-service / payment-service
```

当前没有 HTTP 业务 Controller；`server.port=18082` 主要承载 Spring Web/Actuator，业务入口是 gRPC `19082`。

主要分层：

| 层 | 代码 | 作用 |
|---|---|---|
| 契约 | `proto/im/im.proto` | 9 个 gRPC 方法 |
| gRPC 入口 | `grpc/ImGrpcService` | 覆盖 6 个方法 |
| 回调归一化 | `adaptor/*`、`model/ImEvent` | 原始 JSON 转内部事件 |
| 业务处理 | `service/*` | token、回调、安检、消息后处理、在线态 |
| 数据访问 | `manager/*`、`mapper/*` | Redis ZSet、MyBatis-Plus |
| 外部依赖 | `client/*` | OpenIM HTTP、user/payment gRPC |
| 后台任务 | `job/PresenceSweepJob` | 孤儿会话清扫 |

## 二、功能与维护流程清单

| 编号 | 入口/触发 | 主要代码 | 状态 |
|---|---|---|---|
| F001 | 用户获取 IM token | gateway → `getImToken` → `TokenService` | 部分实现 |
| F002 | 直接 gRPC 获取通话 token | `generateCallToken` → `TokenService` | 源码/契约类型不一致；gateway 未接入 |
| F003 | 业务代发消息 | `sendMessage` | 占位 |
| F004 | OpenIM 原始回调 | `onRawCallback` → `CallbackService` | 内部部分实现，入口/适配器不可达 |
| F004-A | before-send | `BeforeSendHandler` | 部分实现 |
| F004-B | after-send | `MessageSentHandler` | 部分实现且写库失败 |
| F004-C | online | `PresenceService.online` | 内部实现 |
| F004-D | offline | `PresenceService.offline` | 内部实现 |
| F005 | 查询新上线 | `listOnlineUsers` | 已实现，有语义风险 |
| F006 | 查询最近下线 | `listRecentOfflineUsers` | 已实现，有语义风险 |
| F007 | 建会话 | proto + match client | 服务端缺失 |
| F008 | 发系统消息 | proto + match client | 服务端缺失 |
| F009 | 触发 DH 开场白 | proto + match client | 服务端缺失 |
| S001 | 用户类型缓存 | `UserServiceClient` | 已实现 |
| S002 | 异步扣费 | `CoinChargeDispatcher` | 进程内部分实现 |
| S003 | 自定义通知 | `NotificationService` | 部分实现 |
| J001 | 孤儿会话清扫 | `PresenceSweepJob` | 已实现，有竞态 |

## 三、F001 获取 IM 登录凭证

### 3.1 完整调用链

```text
GET /api/v1/im/token
 -> mobile-gateway ImController.getImToken
 -> ImServiceImpl.getImToken
 -> ImClient.getImToken
 -> ImGrpcService.getImToken
 -> TokenService.getImToken
 -> OpenImApiClient.getUserToken
 -> OpenIM POST /auth/get_user_token
```

### 3.2 第一步：gateway 认证与请求组装

1. `ImController.getImToken` 从 `RequestContext.current()` 读取已认证 userId。
2. `ImServiceImpl.getImToken` 调 `ImClient.getImToken(userId, "", "")`，昵称和头像固定为空。
3. `ImClient.createStub` 每次建立一个 plaintext `ManagedChannel`，没有缓存、关闭和 deadline。
4. 默认连接 `im.service.grpc.port:19091`；IM Service 默认是 `19082`。如果 Nacos/环境没有覆盖，调用失败。
5. 异常被 gateway catch 后转换为空 `ImTokenVO`，HTTP 仍走 `Result.ok(...)`。

### 3.3 第二步：gRPC 入口

1. `ImGrpcService.getImToken` 读取 userId、nickname、avatarKey。
2. 没有校验 userId 是否大于 0，也不校验空昵称。
3. 调用 `TokenService.getImToken`。
4. 服务返回 null token 时，builder 写入空字符串，expireSeconds 写 0。
5. 只有 Java 异常逃出 service 时才返回 gRPC INTERNAL；OpenIM 客户端大多数错误已被吞成 `Optional.empty()`。

### 3.4 第三步：拼头像并请求 OpenIM

1. `TokenService` 把 `Long userId` 转字符串。
2. avatarKey 为空则头像 URL 为空；非空则拼硬编码地址：

```java
return "https://minio-api.jianjiange.site/" + avatarKey;
```

3. `OpenImApiClient.getUserToken` POST `/auth/get_user_token`，body 含 userID、adminSecret、platform=1。
4. 2xx 时解析根节点的 `token` 和 `expireTimeSeconds`。
5. 非 2xx 设计上检查 500，并先注册再递归调用；但默认 `RestTemplate` 会对 4xx/5xx 抛异常，执行直接进入 catch，返回 empty。
6. 即使未来允许取得 500 response，递归也没有 retryCount，不能证明“只重试一次”。

### 3.5 注册辅助链路

```text
getUserToken 收到可处理的 500
 -> registerUser
 -> getAdminToken
 -> POST /user/user_register
 -> 再次 getUserToken
```

1. `getAdminToken` POST `/auth/user_token`，每次现取，没有缓存和过期刷新。
2. 管理 token 为空时注册直接失败。
3. 注册 2xx 视为成功。
4. 代码还想把响应包含 registered/exist 的非 2xx 视为幂等成功，但默认错误处理器同样可能提前抛异常。

### 3.6 存储、事务与结果

- 不写 DB、Redis 或 MQ。
- 没有本地事务。
- 成功结果依赖 OpenIM 返回字段。
- 失败常被降级为空 token，而不是明确失败。

### 3.7 实现状态

部分实现。正常 2xx 获取 token 路径存在；懒注册、失败语义、gateway 连接管理和用户资料传递不完整。

## 四、F002 生成通话 token

### 4.1 服务端调用链

```text
ImGrpcService.generateCallToken
 -> TokenService.generateCallToken
 -> JJWT 本地签名
```

### 4.2 第一步：入口类型

1. proto 的 `peer_id` 是 `int64`，生成代码 `getPeerId()` 返回 `long`。
2. 当前 `ImGrpcService` 写成：

```java
String peerId = request.getPeerId();
```

3. 这是源代码与契约的编译期类型冲突。当前 Maven 验证又因离线缺少 `jjwt-impl/jjwt-jackson` 未进入 compile 阶段，但生成源码已经确认返回 `long`。

### 4.3 第二步：JWT 组装

假设先修正类型：

1. 只检查 apiKey 是否为空；secretKey 为空会在 `Keys.hmacShaKeyFor` 抛异常，最后返回空字符串。
2. 房间名为 `call_<userId>_<peerId>`。
3. 当前用户作为 JWT subject，apiKey 作为 issuer。
4. 有效期固定 30 分钟。
5. claims 同时放顶层 room、grants 和嵌套 video grants；普通用户得到 roomAdmin、roomCreate、roomJoin、canPublish、canSubscribe。
6. 任意异常被 catch，返回空 token。

### 4.4 第三步：gateway 现实路径

```text
GET /api/v1/call/token
 -> ImController.getCallToken
 -> ImServiceImpl.getCallToken
 -> new CallTokenVO()
```

gateway 没有调用 `ImClient`，`ImClient` 也没有生成通话 token 的方法。因此用户 HTTP 路径当前必然返回空对象。

### 4.5 一致性与安全风险

- A 请求 B 得到 `call_A_B`，B 独立请求 A 得到 `call_B_A`，双方不能保证进入同一房间。
- 没有 callId、邀请状态或参与方授权校验。
- 权限不是最小化。
- 没有集成测试证明手写 claims 能被当前 LiveKit 接受。

### 4.6 实现状态

服务端为部分实现且当前源码存在编译阻断；产品入口占位。

## 五、F003 代发普通消息

### 5.1 完整调用链

```text
ImGrpcService.sendMessage
 -> 记录日志
 -> 构造伪响应
```

### 5.2 逐步过程

1. 入口读取 senderId、receiverId、content、messageType。
2. 不做参数校验和鉴权。
3. TODO 注释列出“构建消息、调 OpenIM、落库”，但没有执行。
4. messageId 由 `"msg_" + System.currentTimeMillis()` 生成。
5. createdAt 为当前 epoch 秒。
6. 直接 `onNext/onCompleted`。

### 5.3 外部副作用与事务

没有 OpenIM、DB、Redis、MQ、payment 或 user-service 副作用；没有事务、幂等或可靠性。

### 5.4 实现状态

占位。返回成功不代表消息被发送。

## 六、F004 原始回调总入口

### 6.1 设计调用链

```text
OpenIM webhook
 -> mobile-gateway 透传（仓库未找到）
 -> ImGrpcService.onRawCallback
 -> CallbackService.handleRawCallback
 -> ImProviderAdaptorManager.parse
 -> ImEvent
 -> 对应 handler
 -> 业务 code
```

### 6.2 第一步：gRPC 入口

1. 读取 provider 和 payload bytes。
2. 没有 provider 白名单、payload 大小限制、签名验证或来源鉴权。
3. 调 `CallbackService`。
4. 返回 code，并通过 `ImErrorCode.getMessage` 映射英文消息。
5. 未捕获的异常变成 gRPC INTERNAL。

### 6.3 第二步：适配器选择

1. manager 注入 `List<ImProviderAdaptor>`。
2. 遍历并调用 `supports(provider)`。
3. 适配器异常被 catch，继续到最终 unsupported。
4. 无匹配返回 `UnknownEvent(type="unsupported")`。

当前关键事实：

```java
public class OpenImAdaptor {
```

而不是：

```java
public class OpenImAdaptor implements ImProviderAdaptor {
```

所以它不是 manager 列表中的接口实现。即使 gRPC 被调用，事件也会成为 unsupported。

### 6.4 第三步：OpenIM JSON 解析（修正装配后）

`OpenImAdaptor` 支持四个 callbackType：

- `callbackBeforeSendMsg`
- `callbackAfterSendMsg`
- `callbackUserOnlineCommand`
- `callbackUserOfflineCommand`

消息字段从 `msgData` 提取。`sendTime` 从毫秒除以 1000，成为秒；上线/下线时间不转换，按毫秒使用。用户 ID 非数字时返回 null。解析异常内部转 `UnknownEvent(parse_error)`。

### 6.5 第四步：事件分发

`CallbackService` 的 switch：

- before-send：返回 handler 的业务 code。
- after-send：执行副作用后返回 0。
- online/offline：更新在线状态后返回 0。
- unknown：记录 INFO 后返回 0。

### 6.6 实现状态

回调内部模型与 handler 存在，但当前 gateway 入口和适配器装配均未闭合。不能视为已运行链路。

## 七、F004-A 发送前安检

### 7.1 调用链

```text
MessageBeforeSendEvent
 -> BeforeSendHandler.handle
 -> ContactInfoDetector.detect
 -> PaymentServiceClient.getBalance / consumeCoins
 -> CoinChargeDispatcher.dispatch（异步模式）
```

### 7.2 第一步：发送方判定

1. fromUserId 为 null：WARN，返回 0。
2. 调私有 `isDigitalHuman`。
3. 当前实现固定 `return false`，没有注入 `UserServiceClient`。
4. 所以所有非 null 发送方继续安检与扣费，DH 豁免只是 TODO 意图。

### 7.3 第二步：反导流

1. `antiFunnelEnabled` 默认 true。
2. 只有 `event.getMsgType() == 1` 执行。
3. 内容 null/empty 返回未命中。
4. 按 Instagram → Facebook → WhatsApp → Telegram → US phone 顺序匹配。
5. 命中立刻返回 5002，后续不查余额。

关键限制：

- 使用 `String.matches(".*...*")` 做整串正则。
- 美国号码规则并没有代码注释所称的严格前后边界。
- 图片、语音里的联系方式不处理。

### 7.4 第三步：异步扣费预检

默认 `charge.enabled=true`、`coin-cost=6`、`async=true`：

1. 调 `PaymentServiceClient.getBalance(userId)`。
2. client 构造 `GetBalanceRequest` 并使用 blocking stub。
3. `GrpcClientConfig` 给 payment stub 附加 `x-service-name=im-service` 和 `x-internal-token`；本服务 yml 没有该 token 的默认配置项，需由外部配置提供，否则发送空值。
4. 没有实际 deadline；声明的 `READ_TIMEOUT_MS=800` 未使用。
5. RPC 异常被 client catch，返回 null。
6. null 时 handler 记录 WARN 并返回 0。
7. balance < cost 返回 5003。
8. balance >= cost 调 `CoinChargeDispatcher.dispatch`，随后立即返回 0。

### 7.5 第四步：同步扣费分支

当 async=false：

1. 调 `consumeCoins(userId, cost, messageId)`。
2. 请求的幂等键是 `im-msg:<messageId>`。
3. payment 返回 success=true → OK。
4. success=false 且 code=3001 → INSUFFICIENT。
5. 其他业务码或 RPC 异常 → FAILED。
6. handler 分别映射为 0、5003、5004。

### 7.6 事务、幂等和失败

- IM Service 没有数据库事务。
- async 投递仅在进程内存，放行后崩溃会丢任务。
- 固定 2 线程池使用无界队列，没有关闭和队列监控。
- messageId 为空仍会生成 `im-msg:`。
- 下游幂等可以防重复扣，不能防本地任务丢失。

## 八、S002 异步金币扣减

### 8.1 调用链

```text
CoinChargeDispatcher.dispatch
 -> fixedThreadPool.execute
 -> PaymentServiceClient.consumeCoins
 -> payment-service ConsumeCoins
 -> 日志
```

### 8.2 逐步过程

1. handler 把 userId、amount、messageId 捕获进 Runnable。
2. executor 把任务放入无界 `LinkedBlockingQueue`。
3. worker 调 payment blocking stub。
4. OK 记录 DEBUG。
5. INSUFFICIENT 记录 WARN。
6. FAILED 或异常记录 ERROR。
7. 不重试、不落任务表、不回调消息状态。

### 8.3 当前一致性模型

消息放行与金币扣减是非原子的。payment-service 的幂等键负责“同一任务重复执行时最多生效一次”，IM Service 不保证“每个已放行消息至少执行一次扣费”。

## 九、F004-B 消息发送后处理

### 9.1 完整调用链

```text
MessageSentEvent
 -> MessageSentHandler.handle
 -> saveMessage
 -> MessageManager.save
 -> ChatMessageMapper.insert
 -> UserServiceClient.isDigitalHuman(from)
 -> UserServiceClient.isDigitalHuman(to)
 -> MessageManager.determineRouteType
 -> BH_DH ? AiReplyService.triggerAiReply : 结束
```

### 9.2 第一步：消息实体组装

1. 从事件复制 messageId、from/to、content、msgType、conversationType、provider、timestamp。
2. createdAt 使用当前应用时间。
3. 不设置 convId、metadata、updatedAt 和 `routeType`。
4. 调 `MessageManager.save` plain INSERT。

### 9.3 第二步：数据库实际行为

迁移定义：

```sql
message_id VARCHAR(128) UNIQUE NOT NULL,
from_user_id BIGINT NOT NULL,
to_user_id BIGINT NOT NULL,
route_type VARCHAR(16) NOT NULL
```

因为 routeType 未设置，插入会触发 NOT NULL 失败。异常被 `saveMessage` catch 后只记 ERROR，`handle` 继续执行。

若未来补 routeType：

- 重复 messageId 仍会抛唯一冲突。
- 当前 catch 不区分重复与数据库不可用。
- 没有 update、upsert 或重试。

### 9.4 第三步：用户合法性

1. 保存尝试结束后才检查 from/to 是否 null。
2. 任一为空则 WARN 并结束路由。
3. 由于数据库两列也是 NOT NULL，这类事件的保存也已失败。

### 9.5 第四步：用户类型 Cache-Aside

每个 userId：

1. 读取 `putao:im:user:type:<id>`。
2. 命中 `"1"` 返回 DH，其他缓存值返回 BH。
3. 未命中则同步调 user-service `getUserType`。
4. 成功后写 `"1"`/`"0"`，TTL 10 分钟。
5. RPC 失败返回 false（BH），但不把失败结果写缓存。

因此故障时会对 from/to 重复发起下游 RPC，并把真实 DH 当成 BH。

### 9.6 第五步：路由计算

`determineRouteType` 仅根据两个 boolean 返回四种字符串。注意路由结果没有回写前面已经尝试保存的 entity，所以即使 DB schema允许 null，记录也不会包含真实 route。

### 9.7 第六步：AI 占位回复

只在 BH_DH：

1. 当前回调线程 `Thread.sleep(2000)`。
2. 固定回复 `"Hello! This is an AI reply."`。
3. 调 `NotificationService.sendBusinessNotification(dh, bh, "text", data)`。
4. 通知服务忽略 `"text"` key，只把 `{"content": ...}` 作为 custom message 发送。
5. 失败被 AI service catch，不影响回调最终返回 0。

### 9.8 事务与可见结果

- 没有事务覆盖“消息镜像 + AI 任务”。
- OpenIM 消息已经投递；本地失败无法回滚。
- 回调最终仍返回 0，OpenIM 不会因本地写库失败而重试。
- 当前没有可靠消息落库保证。

## 十、S003 自定义通知发送

### 10.1 调用链

```text
NotificationService.sendTyping / sendStopTyping / sendBusinessNotification
 -> getAdminToken
 -> ObjectMapper.writeValueAsString(data)
 -> OpenImApiClient.sendMsg
 -> OpenIM POST /msg/send_msg
```

### 10.2 逐步过程

1. 若配置 `openim.admin-token` 非空，直接使用。
2. 否则同步调用 `/auth/user_token` 获取 admin token。
3. data 序列化为 JSON。
4. `notificationKey` 不进入 JSON。
5. `OpenImApiClient.sendMsg` 构造 msgType=100、textElem.text=jsonData。
6. 2xx 时取 `data.serverMsgID`。
7. 无 messageId 或异常仅记日志，方法无返回值。

### 10.3 当前边界

- typing refresh/onset 配置无人使用。
- `MATCH_SUCCESS`、`MATCH_WELCOME` 常量没有调用方。
- admin token 没有缓存或失效重试。
- `RestTemplate` 没有 timeout。

## 十一、F004-C 用户上线

### 11.1 调用链

```text
UserOnlineEvent
 -> PresenceService.online @Transactional
 -> PresenceRedisManager.markOnline
 -> ZADD NX + ZSCORE
 -> first ? UserOnlineSessionMapper.insert : 结束
```

### 11.2 第一步：时间转换

1. onlineAt 按 epoch ms 转 UTC `OffsetDateTime`。
2. 0 会转换成 1970-01-01，不做合法性校验。

### 11.3 第二步：Redis 首次上线判定

1. key 是 `putao:im:presence:online`。
2. 调 `addIfAbsent(member=userId, score=onlineAt)`，但忽略返回 Boolean。
3. 再读 score。
4. `score != null && score.longValue() == onlineAt` 判 first。

分支：

- member 不存在且添加成功：通常 first=true。
- member 已存在且 score 不同：first=false。
- 重复事件且 score 与 onlineAt 完全相同：仍 first=true，可能重复开 PG 会话。

### 11.4 第三步：PG 开会话

first=true 时：

1. 新建 `UserOnlineSessionEntity`。
2. 写 userId、platform、onlineAt。
3. offlineAt 默认为 null。
4. INSERT 后提交事务。

数据库没有唯一约束防止同用户多条打开会话。

### 11.5 事务和双写

Spring 事务只管理 PG：

- Redis 成功、PG 失败：PG 回滚，Redis 不回滚。
- Redis 抛异常：方法退出，PG 尚未写。
- 没有补偿任务修复“Redis 有、PG 无”。

## 十二、F004-D 用户下线

### 12.1 调用链

```text
UserOfflineEvent
 -> PresenceService.offline @Transactional
 -> ZSCORE
 -> 查最近一条 open PG session
 -> update offlineAt/duration
 -> ZREM
```

### 12.2 第一步：依赖 Redis 取得起点

1. 读取 userId 的 ZSet score。
2. 不存在时只 WARN，既不查 PG，也不 ZREM。
3. 因此 Redis 丢数据会让 PG 开会话永久悬空，等待 sweep。

### 12.3 第二步：计算时长

1. `duration = (offlineAt - since) / 1000`。
2. 没有 clamp；乱序事件可产生负数。
3. offlineAt 按毫秒转 UTC。

### 12.4 第三步：关闭 PG 会话

1. 查询 userId 相同且 offlineAt IS NULL。
2. `ORDER BY online_at DESC LIMIT 1`。
3. 不按 platform 匹配。
4. 找到时设置 offlineAt、durationSeconds 并 updateById。
5. 找不到时仍继续 ZREM。

### 12.5 第四步：Redis 移除

无论 PG 是否找到会话，只要最初 ZSCORE 存在，就 ZREM 整个 userId。多端场景下任一端离线会把用户整体标记离线。

### 12.6 事务边界

若 ZREM 后数据库提交失败，PG 回滚但 Redis 已删除；没有补偿。

## 十三、F005 查询时间窗内新上线用户

### 13.1 调用链

```text
match-service DhInteractionPlanService.runOnlinePlan
 -> ImServiceClient.listOnlineUsers
 -> ImGrpcService.listOnlineUsers
 -> PresenceService.listOnlineUsers
 -> PresenceRedisManager.listOnlineUsers
 -> ZRANGEBYSCORE
```

### 13.2 第一步：消费方游标

1. match-service 从 Redis 读取在线游标。
2. 游标缺失时用 now-60s。
3. 游标早于 now-30min 时也重置为 now-60s。
4. 请求 `[cursor, now]`、limit=5000。

### 13.3 第二步：服务端 limit

1. request.limit > 0 原样使用。
2. 否则默认 5000。
3. 没有实现 proto 注释中的 50000 上限。
4. since/until 未校验，逆序时返回空结果而不是参数错误。

### 13.4 第三步：Redis 查询

```java
rangeByScoreWithScores(key, since, until, 0, limit)
```

Spring Data 此范围两端都包含。结果按 score 升序，member 非数字时过滤，然后转 Long。

### 13.5 第四步：消费方推进

match-service client 捕获任意异常并返回空 list。`runOnlinePlan` 随后无条件把游标写成 now，再逐用户生成计划。

失败/截断影响：

- RPC 失败：这一窗口被跳过。
- 超过 5000：未返回的用户被跳过。
- 端点相同：因为闭区间，下轮可能重复；下游 cooldown/任务去重可减少副作用，但接口本身不是无重复。

### 13.6 一致性模型

这是至多一页的时间窗扫描，不是可靠分页。没有 nextCursor、hasMore 和成功确认。

## 十四、F006 查询时间窗内最近下线用户

### 14.1 调用链

```text
match-service DhInteractionPlanService.runOfflinePlan
 -> ImServiceClient.listRecentOfflineUsers
 -> ImGrpcService.listRecentOfflineUsers
 -> PresenceService.listRecentOfflineUsers
 -> UserOnlineSessionMapper.selectList
```

### 14.2 第一步：消费方窗口

1. offlineUntil = now - offlineThreshold。
2. 下界取 Redis 游标与 lookbackFloor 的较大值。
3. 请求 limit=5000。

### 14.3 第二步：服务端时间转换与 SQL

1. since/until 从 epoch ms 转 UTC。
2. MyBatis 条件使用 `.between(offlineAt, sinceUtc, untilUtc)`，两端包含。
3. 按 `user_id DESC` 排序。
4. 拼接 `"LIMIT " + limit`。
5. 逻辑删除插件通常补 `deleted=0`。

风险：

- 正数 limit 无上限；极大值带来重查询。
- limit 来自 int 且只接受 >0，否则入口改为 5000，因此这里通常不会注入任意 SQL，但仍是字符串拼接。
- 排序不是 offlineAt，无法作为时间游标的稳定页序。

### 14.4 第三步：Java 去重

数据库 limit 之后才：

```java
map(userId).distinct().collect(toList())
```

同一用户多次下线会占多个 DB 名额，最终结果可能远少于 limit。

### 14.5 第四步：消费方推进

无论调用失败、空结果或截断，match-service 都写入 offlineUntil。其 client 把异常降级为空 list，因此会静默丢窗口。

## 十五、F007/F008/F009 配对副作用缺失实现

### 15.1 现有调用方

match-service `ImServiceClient` 已实现：

- `ensureConversation`
- `sendSystemMessage`
- `triggerDhOpening`

这些调用会由 match-service Outbox 重试。

### 15.2 服务端结果

`ImGrpcService` 没有 override 这三个方法。gRPC 基类默认返回 `UNIMPLEMENTED`：

1. match worker 发起 RPC。
2. IM Service 返回 UNIMPLEMENTED。
3. client 抛异常。
4. match Outbox 记录失败并重试。
5. 只要下游仍未实现，任务不可能成功。

不能把它们描述为“TODO 方法返回占位结果”；它们在服务端连方法体都没有。

## 十六、J001 孤儿会话清扫

### 16.1 调度调用链

```text
Spring @Scheduled
 -> ShedLock JDBC 尝试获得 presenceSweep
 -> PresenceSweepJob.sweep
 -> PresenceService.sweepOrphanSessions @Transactional
 -> 查 PG 孤儿
 -> 逐条 UPDATE + Redis ZREM
```

### 16.2 第一步：调度和锁

1. `@EnableScheduling` 开启调度。
2. cron 默认 `0 */30 * * * *`。
3. enabled=false 时直接跳过。
4. ShedLock 使用 PG `shedlock` 表和 DB 时间。
5. 此任务 `lockAtMostFor=PT5M`，覆盖全局默认 PT2M。
6. 没有 lockAtLeastFor。

### 16.3 第二步：阈值与扫描

1. threshold = 当前毫秒 - maxOnlineHours。
2. 查询 `offline_at IS NULL AND online_at <= threshold`。
3. 没有显式 limit、排序或逻辑删除条件；逻辑删除由 MyBatis-Plus 注入。
4. 全部结果在一个事务中加载。

### 16.4 第三步：逐条关闭

每个 session：

1. offlineAt 设置为 `session.onlineAt + maxOnlineHours`。
2. durationSeconds 固定为 `maxOnlineHours * 3600`。
3. `updateById`。
4. `redisManager.markOffline(userId)`。
5. 记录 INFO。

### 16.5 事务、并发与失败

- PG 更新在单事务里；Redis 删除不受事务管理。
- 过程中异常会使 PG 回滚，但之前的 ZREM 不恢复。
- 同用户的旧孤儿会话可能与当前新的 Redis 在线状态并存；无条件 ZREM 会误删新会话。
- 超过 5 分钟后锁可过期，另一个实例可能并发进入；行更新没有 compare-and-set 条件。
- job catch 总异常后只记 ERROR，下一 cron 再试。

## 十七、存储与异步设施清单

### 17.1 PostgreSQL

| 对象 | 写入方 | 读取方 | 当前作用 |
|---|---|---|---|
| `chat_messages` | after-send | `MessageManager.findByMessageId`（当前无调用） | 消息镜像，当前写入被 route_type 阻断 |
| `user_online_session` | online/offline/sweep | recent-offline/sweep | 在线历史 |
| `shedlock` | ShedLock | ShedLock | 多实例调度锁 |

### 17.2 Redis

| Key | 写 | 读 | TTL |
|---|---|---|---|
| `putao:im:presence:online` | online/offline/sweep | offline、online query、onlineCount | 无 |
| `putao:im:user:type:<id>` | user-service 查询成功后 | after-send 路由 | 10 分钟 |

### 17.3 MQ 与线程池

- RocketMQ：pom 和 yml 有配置，但没有 producer/consumer。
- `CoinChargeDispatcher`：2 线程、无界队列，真实使用。
- `BeforeSendHandler.chargeExecutor`：2 线程，未使用。
- AI 回复：没有独立 executor，在回调线程中 sleep。

## 十八、实现状态与风险表

| 能力 | 状态 | 核心证据/风险 |
|---|---|---|
| IM token 正常获取 | 部分实现 | 2xx 路径存在；失败返回空 |
| OpenIM 懒注册 | 未闭环 | 500 被默认错误处理器抛出；递归无次数 |
| 通话 token | 阻断 | peerId 类型编译冲突；gateway 未调用 |
| 普通消息发送 | 占位 | 伪造 messageId |
| callback 入口 | 缺失 | gateway 无 webhook/透传代码 |
| Provider 装配 | 阻断 | `OpenImAdaptor` 未实现接口 |
| 反导流 | 内部部分实现 | 只有链路修复后才可能生效 |
| DH 发送豁免 | TODO | 固定 false |
| 异步扣费 | 不可靠 | 无任务持久化/重试/deadline |
| 消息落库 | 阻断 | route_type 未设置 |
| AI 回复 | 占位 | 固定文本、同步 sleep |
| typing 续命 | 未实现 | 只有方法和配置 |
| 在线双写 | 部分实现 | 跨 Redis/PG 非原子 |
| 在线/离线查询 | 已实现有风险 | 边界、limit、排序、游标推进 |
| 建会话/系统消息/DH 开场白 | 缺失实现 | gRPC UNIMPLEMENTED |
| Presence sweep | 已实现有风险 | 全量单事务、旧会话误删新在线态 |
| RocketMQ | 配置未使用 | 无生产/消费代码 |
| Redisson | 配置未使用 | 业务没有引用 |
| 业务 Micrometer 指标 | 未实现 | 只有 actuator/registry 依赖 |
| 自动化测试 | 缺失 | `src/test` 无文件 |

## 十九、功能到代码索引

| 主题 | 代码 |
|---|---|
| gRPC 契约 | `proto/im/im.proto` |
| gRPC 服务端 | `im-service/.../grpc/ImGrpcService.java` |
| gateway 用户入口 | `mobile-gateway/.../controller/ImController.java` |
| gateway 调用 | `mobile-gateway/.../service/impl/ImServiceImpl.java`、`client/ImClient.java` |
| 回调总分发 | `service/CallbackService.java` |
| OpenIM 解析 | `adaptor/OpenImAdaptor.java` |
| 适配器管理 | `adaptor/ImProviderAdaptorManager.java` |
| 发送前规则 | `service/BeforeSendHandler.java`、`ContactInfoDetector.java` |
| 金币调用 | `client/PaymentServiceClient.java`、`service/CoinChargeDispatcher.java` |
| 发送后规则 | `service/MessageSentHandler.java`、`manager/MessageManager.java` |
| 用户类型 | `client/UserServiceClient.java` |
| AI 占位 | `service/AiReplyService.java`、`NotificationService.java` |
| token | `service/TokenService.java`、`client/OpenImApiClient.java` |
| 在线状态 | `service/PresenceService.java`、`manager/PresenceRedisManager.java` |
| 清扫任务 | `job/PresenceSweepJob.java`、`config/ShedLockConfig.java` |
| 数据库 | `resources/db/migration/V1__init_im_tables.sql`、`V2__init_shedlock_table.sql` |
| match 消费方 | `match-service/.../client/ImServiceClient.java`、`service/DhInteractionPlanService.java` |
