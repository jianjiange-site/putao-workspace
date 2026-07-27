# im-service 功能设计文档（类 PRD）

> 配套：[`README.md`](./README.md)、[`knowledge.md`](./knowledge.md)、[`interview-qa.md`](./interview-qa.md)
>
> im-service 是 dating app 后端的"即时通讯 + 通话"中枢,负责对接外部 OpenIM 引擎 + LiveKit 实时音视频服务,在 IM 引擎回调前后插入业务逻辑(反导流/扣费/AI 自动回复/在线状态),并对外提供 9 个 gRPC 接口供其他服务调用。

---

## 模块概述

### 业务定位

im-service 在 dating app 系统中的位置:

- **上游**: 用户 App(通过 mobile-gateway) + match-service(配对成功触发建会话) + ai-chat-service(数字人对话能力)
- **下游**: OpenIM(消息引擎) + LiveKit(实时音视频) + payment-service(聊天扣费) + user-service(用户类型查询)

```
                              ┌─────────────┐
                              │ match-service │ ← 配对成功 / DH 模拟 like
                              └──────┬──────┘
                                     │ gRPC: EnsureConversation / SendSystemMessage / TriggerDhOpening
                                     ▼
┌──────────┐   WS/HTTP     ┌──────────────┐  REST API    ┌──────────────┐
│ Mobile App │ ─────────► │ mobile-gateway │ ─────────► │ OpenIM       │
└──────────┘              └────────┬───────┘              └──────┬───────┘
                                   │ gRPC OnRawCallback          │ 回调
                                   ▼                             │
                            ┌──────────────┐                    │
                            │  im-service   │ ◄──────────────────┘
                            │  (本服务)     │
                            └──┬───────┬────┘
                               │       │  gRPC
                  gRPC consumeCoins    gRPC isDigitalHuman
                               ▼       ▼
                       payment-service user-service
```

### 核心价值

解决三件事:

1. **IM 引擎适配层**: 抹平 OpenIM(以及未来可能的腾讯 IM / 声网 IM)的协议差异,统一 callback → 内部 ImEvent 归一化事件 → 业务 handler。
2. **IM 业务规则落地点**: 在 before-send 阶段做反导流(屏蔽 Instagram/WhatsApp/美号) + 聊天扣费(异步扣金币); 在 after-send 阶段做 BH→DH 路由 → AI 自动回复。
3. **在线状态权威源**: 维护 `im:presence:online` Redis ZSet 和 `user_online_session` PG 表,供 match-service 的 DH 计划(OnlinePlanGenerator / OfflinePlanGenerator)拉取新人。

### 用户角色

| 角色 | 使用场景 |
|------|---------|
| BH(真人) | App 内收发消息、配对后 IM 聊天、发起通话 |
| DH(数字人) | 不直接使用 App,由 ai-chat-service 代发,im-service 看到 from_id 是 DH 时跳过安检 |
| match-service 管理员 | 配对成功后调用 EnsureConversation / SendSystemMessage 建会话 |
| ai-chat-service | BH→DH 消息触发 AI 续命 typing + 自动回复 |
| OpenIM 引擎 | 回调 before-send / after-send / online / offline 四类事件 |
| LiveKit 引擎 | 实时音视频房间,im-service 负责签发 JWT |

---

## 功能清单

| 功能编号 | 功能名称 | 功能描述 | 优先级 |
|---------|---------|---------|--------|
| F001 | 用户上线/下线 | 接收 OpenIM 回调,维护 Redis ZSet + PG 会话 | P0 |
| F002 | 消息发送前安检 | before-send: 反导流检测 + DH 放行 + 扣费预检 | P0 |
| F003 | 消息发送后处理 | after-send: 落库 + AI 路由 | P0 |
| F004 | 异步聊天扣费 | 用 messageId 作幂等键,异步扣 payment 金币 | P0 |
| F005 | 在线状态查询 | 给 match-service 拉在线/离线用户用于 DH 计划 | P0 |
| F006 | OpenIM Token 签发 | 用户首次进入聊天时返回 IM Token | P0 |
| F007 | LiveKit 通话 Token 签发 | 用户发起通话时返回 JWT(30 分钟过期) | P1 |
| F008 | IM Provider 适配 | 不同 IM 引擎的回调归一化(OpenImAdaptor 当前实现) | P0 |
| F009 | 孤儿会话清扫 | 定时任务关掉只上线没下线的会话(默认 26h) | P1 |
| F010 | 业务通知下发 | typing/match_success/match_welcome 等 custom msg | P1 |
| F011 | OpenIM 用户懒注册 | 获取 Token 时 500 → register → retry 一次 | P1 |
| F012 | 用户类型本地缓存 | UserServiceClient 缓存 BH/DH 判断 | P2 |

> **状态说明**: F001/F002/F003/F004/F005/F006/F008 是 P0 当前实现;F007/F009/F010/F011 是 P1 已实现但占位/简化;F012 是 P2 优化项。`SendMessage` / `EnsureConversation` / `TriggerDhOpening` 是 proto 定义但**当前 grpc 实现是占位 TODO**(见 `ImGrpcService.sendMessage` 注: 实际生产通过 OpenIM 直连 mobile-gateway,im-service 只接回调)。

---

## 功能详情

### 功能点 1：用户上线/下线 (F001)

#### 1. 功能描述
接收 OpenIM 回调 `callbackUserOnlineCommand` / `callbackUserOfflineCommand`,维护两层数据源:
- Redis ZSet `putao:im:presence:online`(score = onlineAt epoch ms) — 高速读路径,供 ListOnlineUsers gRPC 实时返回;
- PG 表 `user_online_session`(offline_at=NULL 表示在线) — 持久化,供 OfflinePlanGenerator 拉下线用户 + sweep 兜底。

#### 2. 业务规则
- 数字人(DH)不上此表 — 由 OpenIM 触发回调,DH 没"上线"概念;
- 上线时 `ZADD NX`(已在线不重复记 score),只有真正首次上线才插 PG 行;
- 下线时按 `user_online_session.offline_at IS NULL` 找最近一条,把 offline_at + duration_seconds 回填;
- 同用户多端登录(如 iOS + Android 同时)只算一条 session,后续登录不重复开 session。

#### 3. 用户交互流程
```
用户打开 App → OpenIM client.connect() → OpenIM 触发回调 callbackUserOnlineCommand
                                                    ↓
                                mobile-gateway 透传 → im-service gRPC OnRawCallback
                                                    ↓
                                OpenImAdaptor.parse → ImEvent.UserOnlineEvent
                                                    ↓
                                CallbackService.handle → PresenceService.online
                                                    ↓
                                PresenceRedisManager.markOnline(ZADD NX)
                                                    ↓
                                UserOnlineSessionMapper.insert(PG 新行)
```

#### 4. 数据流转
```
OpenIM 回调 JSON ──► ImEvent ──► PresenceService ──┬──► Redis ZSet (putao:im:presence:online)
                                                  └──► PG user_online_session (offline_at IS NULL)
```

#### 5. 接口设计

**gRPC: `OnRawCallback`**

|| 参数 | 类型 | 必填 | 说明 |
||------|------|------|------|
|| provider | string | 是 | IM 引擎标识("openim" / 未来"tencent") |
|| payload | bytes | 是 | 原始 JSON |

**返回**:
```json
{
  "code": 0,
  "message": "Success"
}
```

**专用查询 gRPC: `ListOnlineUsers` / `ListRecentOfflineUsers`**

|| 参数 | 类型 | 必填 | 说明 |
||------|------|------|------|
|| since | int64 | 是 | epoch ms,闭区间下界 |
|| until | int64 | 是 | epoch ms,开区间上界 |
|| limit | int32 | 否 | 默认 5000,上限 50000 |

**返回**:
```json
{
  "user_ids": [1024, 1025, 1026]
}
```

#### 6. 边界情况
- **回调乱序**: offline 先到 online 后到 → offline 时 Redis 无 score,只 WARN 不报错;后续 online 正常处理(可能产生"超长 session"被 sweep 兜底)。
- **DH userID**: OpenIM 给的 userID 是字符串,OpenImAdaptor 用 `Long.parseLong` 转;非数字返回 null(WARN)。
- **Redis 抖动**: `ZADD` 抛异常 → 当前实现会让 PG 写入一并回滚(@Transactional);考虑 trade-off,可改为先写 Redis 再写 PG(Redis 是缓存源)。

#### 7. 监控埋点
- `im.presence.online.count` — 当前在线人数(Gauge)
- `im.presence.online.rate_per_min` — 每分钟新上线人数
- `im.presence.session.duration_avg` — 平均 session 时长

---

### 功能点 2：消息发送前安检 (F002)

#### 1. 功能描述
before-send 阶段在 OpenIM 真正发消息前回调 im-service 做四件事:
1. sender 解析失败 → 放行(不因解析 bug 误伤用户);
2. sender 是 DH → 放行(AI 自己发的不扣费不安检);
3. 文本消息过 `ContactInfoDetector` 检测 Instagram / Facebook / WhatsApp / Telegram / 美国号码 → 命中则拒发;
4. 文本消息按 Nacos 配置 `im.message.charge.enabled` + `coin-cost` 异步扣金币(默认 6 金币/条)。

#### 2. 业务规则
- 类型 1 (TEXT) 才走反导流;图片/语音/视频/礼物 不查联系方式(消息体是二进制);
- DH 的发送方 isDh=true → 完全 bypass(0 扣费 0 检测);
- 异步扣费 vs 同步扣费由 `im.message.charge.async` 控制:
  - **async=true(默认)**: 只 `getBalance` 预检;够就放行,后台 `CoinChargeDispatcher` 异步真扣;不够就 REJECT_INSUFFICIENT_COINS;
  - **async=false**: 同步 `consumeCoins` RPC,直接扣;失败返回 FAILED。

#### 3. 用户交互流程
```
用户在 App 聊天框点发送
       ↓
OpenIM 触发 callbackBeforeSendMsg
       ↓
mobile-gateway → im-service gRPC OnRawCallback
       ↓
OpenImAdaptor.parse → MessageBeforeSendEvent
       ↓
CallbackService.handle → BeforeSendHandler.handle
       ↓
1. sender 解析失败? → return OK (放行)
2. sender 是 DH?     → return OK (放行)
3. 文本 + 反导流?    → ContactInfoDetector.detect → 命中 return REJECT_CONTACT_INFO
4. 启用了扣费?      → checkAndCharge → OK / REJECT_INSUFFICIENT_COINS / REJECT_PAYMENT_UNAVAILABLE
       ↓
OpenIM 收到 code 非 0 → 不真正发消息 → App 收到"消息被拒"提示
```

#### 4. 数据流转
```
OpenIM 回调 JSON
     │
     ▼
OpenImAdaptor → ImEvent.MessageBeforeSendEvent(fromId, toId, content, msgType)
     │
     ▼
BeforeSendHandler.handle
     │
     ├──► (if msgType=TEXT) ContactInfoDetector.detect(content) → null / "instagram" / ...
     │
     └──► (if chargeEnabled)
              ├── async=true: payment.getBalance → CoinChargeDispatcher.dispatch(异步扣)
              └── async=false: payment.consumeCoins(messageId作幂等键)
     │
     ▼
return 0 (放行) / 5002 (REJECT_CONTACT_INFO) / 5003 (INSUFFICIENT_COINS) / 5004 (PAYMENT_UNAVAILABLE)
```

#### 5. 接口设计
**内部接口(非 RPC)**: `BeforeSendHandler.handle(ImEvent.MessageBeforeSendEvent) → int`

**返回码对照表**:

|| code | 含义 | App 表现 |
||------|------|---------|
|| 0 | OK | 消息正常发送 |
|| 5002 | REJECT_CONTACT_INFO | 弹"消息包含站外联系方式,已拦截" |
|| 5003 | REJECT_INSUFFICIENT_COINS | 弹"金币不足,无法发送" |
|| 5004 | REJECT_PAYMENT_UNAVAILABLE | 弹"支付服务暂不可用" |

#### 6. 边界情况
- **OpenIM 字段缺失**: sendID 缺失 → 直接放行(不因字段缺失误伤);
- **正则匹配误伤**: 11 位中国手机号 +10 位微信号等会被美号正则误伤 → 需运营反馈调正则(当前实现未做多语言多区域细分);
- **余额刚好等于 coin-cost**: balance >= cost 视为够,不抛;
- **payment-service 不可用**: async 模式返回 OK 让消息发(后台异步扣会失败打 ERROR 日志);sync 模式返回 REJECT_PAYMENT_UNAVAILABLE。

#### 7. 监控埋点
- `im.before_send.reject.contact_info.count` — 反导流拒绝数
- `im.before_send.reject.insufficient_coins.count` — 金币不足拒绝数
- `im.before_send.charge.async_dispatch.count` — 异步扣费投递数
- `im.before_send.charge.async_fail.count` — 异步扣费失败数

---

### 功能点 3：消息发送后处理 (F003)

#### 1. 功能描述
after-send 阶段: 消息已成功发出,im-service 异步做两件事:
1. **落库**: 把消息实体写入 `chat_messages` 表(`messageId` UNIQUE 兜底,重复消息 UPSERT 跳过);
2. **AI 路由**: 根据 from/to 是否 DH 判断消息路由(BH↔DH),只 BH→DH 触发 `AiReplyService.triggerAiReply`。

#### 2. 业务规则
- **DH→BH**: 数字人回复真人 — 当前不触发 AI(已经在 ai-chat-service 处理过);
- **BH→DH**: 真人发给数字人 → 触发 AI 自动回复(模拟真人);
- **BH→BH / DH→DH**: 真人互发或 DH 互发 → 只落库,不触发 AI;
- `route_type` 字段写入 DB(BH_BH / BH_DH / DH_BH / DH_DH / UNKNOWN),用于运营分析 BH 和 DH 聊天活跃度。

#### 3. 用户交互流程
```
OpenIM 真正发出消息 → 触发 callbackAfterSendMsg
       ↓
mobile-gateway → im-service OnRawCallback
       ↓
OpenImAdaptor.parse → MessageSentEvent
       ↓
CallbackService → MessageSentHandler.handle
       ↓
1. MessageManager.save(写 chat_messages,UPSERT 兜底)
2. UserServiceClient.isDigitalHuman(from) + isDigitalHuman(to) → 路由
3. if routeType == BH_DH: AiReplyService.triggerAiReply (调 ai-chat + 下发 typing)
```

#### 4. 数据流转
```
MessageSentEvent
   │
   ├──► ChatMessageEntity (messageId UNIQUE, content, type, conversationType, timestamp)
   │         │
   │         ▼
   │     chat_messages INSERT (idempotent on messageId)
   │
   └──► routeType 计算
         │
         ├── BH_BH / DH_BH / DH_DH → 无副作用
         │
         └── BH_DH → AiReplyService.triggerAiReply
                          │
                          ├──► ai-chat gRPC 生成回复文本(分 N 段)
                          ├──► NotificationService.sendTyping(每 3 秒续命)
                          └──► NotificationService.sendBusinessNotification(每段一条)
```

#### 5. 接口设计
**内部接口**: `MessageSentHandler.handle(ImEvent.MessageSentEvent) → void`

**实体表 chat_messages**:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | 自增 |
| message_id | VARCHAR(128) UNIQUE | OpenIM serverMsgID |
| conv_id | BIGINT | 会话 ID(目前未填,OpenIM 那边管) |
| from_user_id | BIGINT | 发送方 |
| to_user_id | BIGINT | 接收方 |
| content | TEXT | 文本内容 / 元数据 |
| type | SMALLINT | 1=文本 2=图片 3=语音 4=视频 5=礼物 |
| route_type | VARCHAR(16) | BH_BH/BH_DH/DH_BH/DH_DH |
| metadata | JSONB | 图片 URL 等 |
| conversation_type | VARCHAR(16) | SINGLE/GROUP |
| provider | VARCHAR(32) | openim / tencent |
| timestamp | BIGINT | 发送时间(epoch 秒) |

#### 6. 边界情况
- **OpenIM 重试回调**: messageId UNIQUE,DB 重复 INSERT 抛 DuplicateKey → 静默吞掉(已存在);
- **AI 服务挂掉**: AiReplyService 内部 catch 异常,不抛 — BH 这次消息没自动回复不影响用户发送成功;
- **fromId/toId 解析失败**: 跳过 AI 路由,只落库(WARN 日志)。

#### 7. 监控埋点
- `im.message.persisted.count` — 落库消息总数
- `im.message.route_type.bh_dh.count` — BH→DH 触发 AI 数
- `im.message.persist.fail.count` — 落库失败数

---

### 功能点 4：异步聊天扣费 (F004)

#### 1. 功能描述
`BeforeSendHandler.checkAndCharge` 在 async 模式下,预检余额够 → 立即放行 → 后台用 `CoinChargeDispatcher` 异步扣费。
`messageId` 作为幂等键传给 payment-service(`idempotent_key = "im-msg:" + messageId`),即使后台重试或回调重发,扣费只发生一次。

#### 2. 业务规则
- 异步扣费 = 先放行后扣;若后台扣费失败,损失 ≤ 单条消息金币(6 金币,人民币约 0.6 元);
- 同步扣费 = 扣成功才放行;失败返回 REJECT;
- 幂等键 `im-msg:<serverMsgID>` 在 payment-service 端做去重(`payment-service.knowledge.md` 的 idempotency 设计);
- 扣费线程池固定 2 线程(`Executors.newFixedThreadPool(2)`),扣费失败 → 打 ERROR 日志,不再 retry。

#### 3. 用户交互流程
```
BeforeSendHandler.checkAndCharge
  balance >= coin-cost → 放行 + 异步扣
       ↓
CoinChargeDispatcher.dispatch(userId, cost, messageId)
       ↓
chargeExecutor (固定 2 线程) submit
       ↓
PaymentServiceClient.consumeCoins(userId, cost, "im-msg:"+messageId)
       ↓
支付服务幂等去重 + 真实扣减
```

#### 4. 权衡
- **为什么默认 async=true**: 用户体验优先 — 不能因为 payment 抖动让消息发不出去;
- **为什么 messageId 作幂等键**: OpenIM 的 serverMsgID 全局唯一,即使回调重发也只会扣一次;
- **为什么不 retry**: 聊天扣费 retry 复杂度高(失败可能有部分扣),而且单条损失小,ERROR 日志够运营发现。

---

### 功能点 5：在线状态查询 (F005)

#### 1. 功能描述
为 match-service 的 DH 模拟计划提供两类 RPC:
- `ListOnlineUsers(since, until, limit)` — 查窗口内**新上线**的真人(读 Redis ZSet,O(logN + limit));
- `ListRecentOfflineUsers(since, until, limit)` — 查窗口内**最近下线**的真人(读 PG `user_online_session.offline_at`,索引扫描)。

#### 2. 业务规则
- ZSet 的 member 是 userId(字符串数字),score 是 onlineAt epoch ms;
- match-service 的 `OnlinePlanGenerator` 每 60s 拉窗口 `[now-2min, now]`,触发 DH drip 计划;
- match-service 的 `OfflinePlanGenerator` 每 20min 拉窗口 `[now-30min, now]`,触发 OFFLINE drip 计划;
- listOnlineUsers 限制 limit ≤ 50000,默认 5000。

#### 3. 数据流转
```
match-service scheduler 触发
       ↓
ImServiceClient.listOnlineUsers(since, until, limit)  (match-service 内部)
       ↓
im-service gRPC: ListOnlineUsersRequest
       ↓
PresenceRedisManager.listOnlineUsers
       ↓
Redis ZSet rangeByScoreWithScores(since, until, 0, limit)
       ↓
返回 user_ids list → match-service 转 BH candidates → DH drip
```

#### 4. 接口设计
(同 F001 的 gRPC 接口定义)

#### 5. 边界情况
- **ZSet member 不是数字**: `isNumericUserId` 过滤掉,避免 Long.parseLong 抛错;
- **窗口无数据**: 返回空 list;
- **DH 在 ZSet**: 当前实现未过滤 — DH 理论上不应该有 OpenIM 上线回调,但若有,会进入 BH drip 候选集。**已知 gap**: 应当 `isDigitalHuman` 过滤,目前 match-service 端有过滤(见 `UserServiceClient.listDhCandidates` 不返回 BH)。

---

### 功能点 6：OpenIM Token 签发 (F006)

#### 1. 功能描述
用户首次进入聊天面板 → App 端调 `getImToken(userId, nickname, avatarKey)` → im-service 调 OpenIM `/auth/get_user_token` 拿到 token → 返回 App。
App 拿到 token 后才能连上 OpenIM 的 WebSocket。

#### 2. 业务规则
- **懒注册**: OpenIM 拿到 500 错误(用户未注册)→ 自动调 `/user/user_register` 注册 → 再 retry `/auth/get_user_token` 一次;
- **头像 URL 拼接**: `avatarKey` 是 MinIO object key,自动拼 `https://minio-api.jianjiange.site/<key>` 完整 URL;
- **失败兜底**: OpenIM 不可用 → 返回 `ImTokenResult(null, 0)`,App 端进聊天时拿到空 token 会再次重试。

#### 3. 数据流转
```
App 首次进入聊天
       ↓
通过 mobile-gateway → user-service(或直接) → im-service gRPC GetImToken
       ↓
TokenService.getImToken(userId, nickname, avatarKey)
       ↓
OpenImApiClient.getUserToken
       ↓
HTTP POST OpenIM /auth/get_user_token
       ↓
2xx → 返回 token
500 → registerUser → retry getUserToken
```

#### 4. 接口设计
**gRPC: `GetImToken`**

|| 参数 | 类型 | 必填 | 说明 |
||------|------|------|------|
|| user_id | int64 | 是 | 业务用户 ID |
|| nickname | string | 否 | 昵称(用于 OpenIM 资料) |
|| avatar_key | string | 否 | MinIO object key |

**返回**:
```json
{
  "im_token": "eyJhbGciOiJIUzI1NiIs...",
  "expire_seconds": 2592000
}
```

#### 5. 边界情况
- **OpenIM admin 拿不到 token**: `getAdminToken` 失败 → registerUser 返回 false → getUserToken 返回空 → App 进聊天失败;
- **Token 过期**: 默认 30 天,App 端过期前主动续签。

---

### 功能点 7：LiveKit 通话 Token 签发 (F007)

#### 1. 功能描述
用户发起语音/视频通话 → App 端调 `generateCallToken(userId, peerId)` → im-service 用 jjwt 库签发 LiveKit JWT(30 分钟 TTL)。
LiveKit 验证 JWT 后才允许用户进入房间 `call_<userId>_<peerId>`。

#### 2. 业务规则
- **TTL = 30 分钟**: 够一次通话;太长有安全风险;
- **grants**: `roomJoin=true / canPublish=true / canSubscribe=true / video={roomAdmin, roomCreate, canPublish, canSubscribe}`;
- **未配置 secret**: 返回空字符串,WARN 日志;
- **签名失败**: 返回空字符串,ERROR 日志。

#### 3. 数据流转
```
App 点击"通话"
       ↓
im-service gRPC GenerateCallToken
       ↓
TokenService.generateCallToken(userId, peerId)
       ↓
Jwts.builder() → HMAC-SHA 签名(secret-key)
       ↓
返回 JWT
       ↓
App 用 JWT 连 LiveKit wss://
       ↓
LiveKit 校验 JWT → 允许进房间
```

#### 4. 接口设计
**gRPC: `GenerateCallToken`**

|| 参数 | 类型 | 必填 | 说明 |
||------|------|------|------|
|| user_id | int64 | 是 | 当前用户 ID |
|| peer_id | int64 | 是 | 对方用户 ID |

**返回**:
```json
{ "token": "eyJhbGciOiJIUzI1NiIs..." }
```

#### 5. 边界情况
- **secret-key 未配**: 返回空 + WARN;
- **LiveKit 端关闭**: im-service 这边 JWT 已签发,LiveKit 校验失败由 App 端处理。

---

### 功能点 8：IM Provider 适配 (F008)

#### 1. 功能描述
im-service 不直接绑死 OpenIM,而是通过 `ImProviderAdaptor` 接口 + `ImProviderAdaptorManager` 分发,把不同 IM 引擎的回调 JSON 解析成统一的 `ImEvent` 内部模型。
当前实现: `OpenImAdaptor`(supported=true,provider="openim");未来可加 `TencentImAdaptor` / `AgoraImAdaptor`。

#### 2. 业务规则
- 适配器返回 4 类事件:`MessageBeforeSendEvent` / `MessageSentEvent` / `UserOnlineEvent` / `UserOfflineEvent`;
- 解析失败的 unknown event → return OK 不阻塞(未知事件不影响主流程);
- 适配器抛异常 → manager 捕获,WARN 日志,返回 `UnknownEvent`;
- `ImEvent` 用 sealed interface 强制编译期穷尽所有 case(`switch` pattern matching)。

#### 3. 数据流转
```
mobile-gateway gRPC OnRawCallback(provider="openim", payload=bytes)
       ↓
CallbackService.handleRawCallback
       ↓
ImProviderAdaptorManager.parse(provider, payload)
       ↓
遍历 adaptors → 找到 supports(provider)=true 的 → adaptor.parse(payload)
       ↓
返回 ImEvent (MessageBeforeSend / MessageSent / UserOnline / UserOffline / Unknown)
       ↓
CallbackService switch 匹配 → 对应 handler
```

#### 4. 接口设计
**`ImProviderAdaptor` 接口**:
```java
public interface ImProviderAdaptor {
    boolean supports(String provider);
    ImEvent parse(byte[] rawPayload);
}
```

**`ImEvent` sealed interface**:
```
ImEvent
├── MessageBeforeSendEvent
├── MessageSentEvent
├── UserOnlineEvent
├── UserOfflineEvent
└── UnknownEvent
```

#### 5. 边界情况
- **OpenIM 字段版本变更**: OpenIM 升级后 callback JSON 字段名变了 → OpenImAdaptor 解析失败 → UnknownEvent → 不影响主流程,但安检/落库/AI 都跳过。需要发版 OpenImAdaptor 同步;
- **多 provider 并存**: manager 遍历所有 adaptor → 第一个 supports=true 的处理 → 可同时支持 openim + tencent 灰度。

---

### 功能点 9：孤儿会话清扫 (F009)

#### 1. 功能描述
应用 crash / OpenIM 异常 / 网络抖动导致 `user_online_session.offline_at` 永远是 NULL,session 时长越积越长。
`PresenceSweepJob` 每 30 分钟跑一次(ShedLock 分布式锁),扫 `online_at < now - 26h` 的孤儿会话,强制 close + duration 封顶 26h。

#### 2. 业务规则
- **阈值 26h**(可配 `im.presence.sweep.max-online-hours`): 高于正常最长在线时长,但留 buffer;
- **ShedLock**: `@SchedulerLock(name="presenceSweep", lockAtMostFor="PT5M")` 多实例防重;
- **封顶时长 = 26h**: 实际可能更短(`now - online_at < 26h` 的话按真实时长);
- **同步 ZREM**: 同步删 Redis ZSet,否则下次 ListOnlineUsers 还会拉到。

#### 3. 用户交互流程
```
@Scheduled cron "0 */30 * * * *" + ShedLock
       ↓
PresenceService.sweepOrphanSessions(26)
       ↓
查 PG: online_at < (now - 26h) AND offline_at IS NULL
       ↓
对每条: update offline_at = online_at + 26h, duration_seconds = 26*3600
       ↓
同步 Redis: markOffline(userId) → ZREM putao:im:presence:online
```

#### 4. 边界情况
- **大量孤儿(>1000)**: 当前实现一条条 update,慢但稳定 — 可改为 batch update;
- **lockAtMostFor 超时**: 5 分钟,极端长事务会释放锁让其他实例接管。

---

### 功能点 10：业务通知下发 (F010)

#### 1. 功能描述
im-service 作为"OpenIM 管理员",用 admin token 下发自定义业务通知:
- **typing** (DH 正在打字): AI 自动回复时每 3 秒续命一次,模拟真人持续输出;
- **match_success / match_welcome**: match-service 配对成功后下发(预留,match-service 当前用 EnsureConversation + SendSystemMessage 路径,未走 NotificationService)。

#### 2. 业务规则
- 用 OpenIM 的 `sendMsg` + msgType=100(custom) + content=JSON;
- admin token 优先用 Nacos 配置(冷启动快),fallback 调 OpenIM `/auth/user_token` 拿;
- 发送失败 → ERROR 日志,不抛(不影响主流程)。

#### 3. 接口设计
**`NotificationService.sendBusinessNotification(senderId, receiverId, key, data)`**

|| 参数 | 类型 | 必填 | 说明 |
||------|------|------|------|
|| senderId | string | 是 | 谁发的(可以是 admin id) |
|| receiverId | string | 是 | 收的一方 |
|| notificationKey | string | 是 | "typing" / "match_success" / "match_welcome" |
|| data | Map | 是 | 通知数据(JSON 序列化) |

#### 4. 边界情况
- **admin token 失效**: getAdminToken 失败 → 返回 ERROR 日志,不抛。

---

### 功能点 11：OpenIM 用户懒注册 (F011)

#### 1. 功能描述
新用户从来没在 OpenIM 注册过 → 调 `getUserToken` 拿到 500 → 自动 `registerUser` → retry `getUserToken`。

#### 2. 业务规则
- registerUser 时传入 nickname + faceURL(从 avatarKey 拼);
- 重复注册: OpenIM 返回 "registered" / "exist" 视为成功;
- 只 retry 一次,失败就放弃(避免无限递归)。

#### 3. 权衡
- **为什么懒注册**: 减化用户注册流程;真正触发时再注册;
- **为什么不批量注册**: 用户量大(几万+),启动时全部 register 慢且浪费。

---

### 功能点 12：用户类型本地缓存 (F012)

#### 1. 功能描述
`UserServiceClient.isDigitalHuman(userId)` 调 user-service 的 `getUserType` gRPC → **带本地 `ConcurrentHashMap` 缓存**。
命中返回 true/false;未命中 RPC 调一次,失败 fallback false(BH)。

#### 2. 业务规则
- 缓存**无 TTL**: 当前实现缓存到进程结束;**已知 gap**: user 被注销或转换类型不感知;
- 失败 fallback BH: 当前实现保守,只查 BH → match-service 的 `OnlinePlanGenerator` 拉的"在线用户"当 BH 用;
- `clearCache()` 方法预留,后续可加事件驱动 invalidate。

#### 3. 权衡
- **为什么不接 Nacos invalidate**: 当前用户类型不会变(DH 是预设),不需要失效;
- **为什么不接 Redis 分布式缓存**: 单服务本地 map 够用;只有多实例不一致问题(新 user 在 instance A 注册,instance B 不知道) — 但 BH/DH 类型是稳定属性,缓存 OK。

---

## 数据模型

### PG 表

#### chat_messages(消息流水)
```sql
CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    message_id      VARCHAR(128) UNIQUE NOT NULL,
    conv_id         BIGINT,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    content         TEXT,
    type            SMALLINT NOT NULL DEFAULT 1,
    route_type      VARCHAR(16) NOT NULL,
    metadata        JSONB,
    conversation_type VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
    provider        VARCHAR(32) DEFAULT 'openim',
    timestamp       BIGINT,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);
CREATE INDEX idx_chat_messages_conv ON chat_messages(conv_id, timestamp DESC) WHERE deleted = 0;
CREATE INDEX idx_chat_messages_from ON chat_messages(from_user_id, timestamp DESC);
CREATE INDEX idx_chat_messages_to ON chat_messages(to_user_id, timestamp DESC);
CREATE INDEX idx_chat_messages_route ON chat_messages(route_type, timestamp DESC) WHERE deleted = 0;
```

#### user_online_session(在线会话)
```sql
CREATE TABLE user_online_session (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    platform            SMALLINT,
    online_at           TIMESTAMPTZ NOT NULL,
    offline_at          TIMESTAMPTZ,
    duration_seconds    INT,
    created_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT DEFAULT 0
);
CREATE INDEX idx_user_online_session_user ON user_online_session(user_id, online_at DESC);
CREATE INDEX idx_user_online_session_offline ON user_online_session(offline_at)
    WHERE offline_at IS NOT NULL AND deleted = 0;
```

#### shedlock(分布式锁,定时任务用)
```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)   PRIMARY KEY,
    lock_until TIMESTAMPTZ   NOT NULL,
    locked_at  TIMESTAMPTZ   NOT NULL,
    locked_by  VARCHAR(255)  NOT NULL
);
```

### Redis Key

| Key | 数据结构 | 用途 | TTL |
|-----|---------|------|-----|
| `putao:im:presence:online` | ZSet | 在线用户(member=userId, score=onlineAt epoch ms) | 无,靠 sweep 兜底 |

### gRPC 接口总览

```protobuf
service ImService {
  rpc SendMessage(SendMessageRequest) returns (SendMessageResponse);
  rpc SendSystemMessage(SendSystemMessageRequest) returns (SendSystemMessageResponse);
  rpc EnsureConversation(EnsureConversationRequest) returns (EnsureConversationResponse);
  rpc TriggerDhOpening(TriggerDhOpeningRequest) returns (TriggerDhOpeningResponse);
  rpc GetImToken(GetImTokenRequest) returns (GetImTokenResponse);
  rpc GenerateCallToken(GenerateCallTokenRequest) returns (GenerateCallTokenResponse);
  rpc OnRawCallback(OnRawCallbackRequest) returns (OnRawCallbackResponse);
  rpc ListOnlineUsers(ListOnlineUsersRequest) returns (ListOnlineUsersResponse);
  rpc ListRecentOfflineUsers(ListRecentOfflineUsersRequest) returns (ListRecentOfflineUsersResponse);
}
```

---

## 状态机/流转

### 消息发送完整状态机

```
[App 发起发送] 
       │
       ▼
[OpenIM: 发送前]
       │
       ├─► callbackBeforeSendMsg → im-service BeforeSendHandler
       │                              │
       │                              ├──► 反导流命中 → REJECT_CONTACT_INFO → [OpenIM 拒发] → App 显示拦截
       │                              │
       │                              ├──► 余额不足 → REJECT_INSUFFICIENT_COINS → [OpenIM 拒发] → App 显示金币不足
       │                              │
       │                              └──► OK → [OpenIM 真正发送]
       │                                              │
       │                                              ▼
       │                                       callbackAfterSendMsg → im-service MessageSentHandler
       │                                                                      │
       │                                                                      ├──► save to chat_messages
       │                                                                      │
       │                                                                      └──► routeType=BH_DH? 
       │                                                                              │
       │                                                                              ├─ YES → trigger AI
       │                                                                              │
       │                                                                              └─ NO  → done
       │
       ▼
[App 收到消息]
```

### 用户在线状态机

```
   callbackUserOnlineCommand              callbackUserOfflineCommand
           │                                          │
           ▼                                          ▼
[Offline] ────────► [Online] ───────────────► [Offline]
                   │                              │
                   │                              ▼
                   │                       ZREM putao:im:presence:online
                   ▼                       UPDATE user_online_session
       ZADD putao:im:presence:online        SET offline_at, duration_seconds
       INSERT user_online_session (offline_at=NULL)
       (ZADD NX 保证多端只记一次)

       ⏰ 26h sweep 兜底: offline_at IS NULL AND online_at < now-26h
                            → 强制 close + ZREM
```

---

## 监控指标

### 业务监控(Micrometer)

| 指标 | 类型 | 说明 |
|------|------|------|
| `im.presence.online.count` | Gauge | 当前在线人数 |
| `im.message.before_send.rejected` | Counter | before-send 拒绝数(by reason) |
| `im.message.persisted` | Counter | after-send 落库数 |
| `im.message.route.bh_dh` | Counter | BH→DH 触发 AI 数 |
| `im.message.charge.async_fail` | Counter | 异步扣费失败数 |
| `im.charge.balance.fetch_fail` | Counter | payment.getBalance 失败 |

### 关键日志

| 场景 | 级别 | 日志样例 |
|------|------|---------|
| 上线 | INFO | "User online: userId=1024, at=1722..." |
| 下线 | INFO | "Session closed: userId=1024, duration=300s" |
| 反导流命中 | INFO | "Contact info detected: type=instagram, from=1024, to=1025" |
| 余额不足 | INFO | "Insufficient coins: userId=1024, balance=3, cost=6" |
| 异步扣费失败 | ERROR | "Failed to charge coins: userId=1024, messageId=..." |
| AI 触发 | INFO | "Triggering AI reply: BH=1024 -> DH=8888" |
| OpenIM 适配失败 | WARN | "Unknown OpenIM callback type: ..." |
| 孤儿会话清扫 | INFO | "Orphan session swept: userId=1024" |