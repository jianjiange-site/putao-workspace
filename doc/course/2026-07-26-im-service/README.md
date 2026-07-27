# im-service 学习笔记

> 学习日期：2026-07-26
> 学习范围：im-service — IM Provider 适配、before/after-send 钩子业务规则、双层在线状态、AI 路由触发、Token 签发

## 本次学习内容

- [业务功能设计](./prd.md) - 12 个核心功能点（用户上下线 / 消息安检 / 异步扣费 / AI 路由 / Token 签发等）
- [核心知识点](./knowledge.md) - 13 个技术知识点（适配器模式、异步幂等、双层在线、ShedLock、typing 续命等）
- [面试问答](./interview-qa.md) - 15 个高频问题 + 3 个场景题 + 5 个常见坑

## 学习目标

- [ ] 理解 im-service 是 OpenIM 引擎和 LiveKit 引擎的"唯一业务封装层"
- [ ] 掌握 IM Provider 适配器模式 + sealed interface 归一化事件
- [ ] 掌握 before-send 钩子（反导流 + 异步扣费）和 after-send 钩子（落库 + AI 路由）
- [ ] 理解双层在线状态（Redis ZSet + PG user_online_session）+ ShedLock sweep 兜底
- [ ] 掌握异步扣费的 messageId 幂等性设计
- [ ] 能讲清 im-service 与 user / payment / match 三方服务的 gRPC 边界

## 关联知识

- 设计文档：`doc/specs/match-service-prd-tech.md`（match-service 调 im-service 的部分）
- 实现计划：`doc/plans/2026-07-18-match-service-p1.md`（DH 模拟计划依赖 im-service 在线状态）
- 核心代码：`dating-server/im-service/src/main/java/com/dating/im/`

## 核心代码地图

```
im-service/src/main/java/com/dating/im/
├── ImApplication.java                      # 启动入口
├── grpc/
│   └── ImGrpcService.java                  # 9 个 gRPC 接口实现(4 个 TODO)
├── service/
│   ├── CallbackService                     # OpenIM 回调分发到 handler
│   ├── BeforeSendHandler                   # 反导流 + 异步扣费
│   ├── MessageSentHandler                  # 落库 + AI 路由触发
│   ├── PresenceService                     # 上下线处理
│   ├── TokenService                        # OpenIM Token + LiveKit JWT
│   ├── AiReplyService                      # DH 自动回复(占位)
│   ├── NotificationService                 # typing/match_success 下发
│   ├── CoinChargeDispatcher                # 异步扣费线程池
│   └── ContactInfoDetector                 # 反导流正则
├── adaptor/
│   ├── ImProviderAdaptor                   # 接口
│   ├── OpenImAdaptor                       # OpenIM 回调 JSON 解析
│   └── ImProviderAdaptorManager            # 多 adaptor 分发
├── model/
│   └── ImEvent                             # sealed interface 归一化事件
├── manager/
│   ├── MessageManager                      # 消息落库 + route_type 计算
│   └── PresenceRedisManager                # Redis ZSet 上下线
├── client/
│   ├── OpenImApiClient                     # OpenIM HTTP REST API
│   ├── UserServiceClient                   # gRPC 调 user-service
│   └── PaymentServiceClient                # gRPC 调 payment-service
├── job/
│   └── PresenceSweepJob                    # 孤儿会话清扫(ShedLock)
├── config/
│   ├── GrpcClientConfig                    # user/payment gRPC stub
│   ├── RedissonConfig                      # 分布式锁客户端
│   ├── RedisConfig                         # RedisTemplate 配置
│   └── ShedLockConfig                      # ShedLock JDBC Provider
├── entity/                                 # 2 个 PG 实体
├── mapper/                                 # 2 个 MyBatis-Plus Mapper
├── constant/                               # ImRedisKey / MessageType / RouteType / NotificationKeys / ImErrorCode
└── exception/                              # BizException / ImErrorCode
```

## 一句话定位

> im-service 是 dating app 后端"IM + 实时音视频"的唯一中枢。它是 **OpenIM 引擎和 LiveKit 通话引擎的唯一业务封装层**，对外提供 9 个 gRPC 接口给 match-service / user-service / payment-service 调用；内部通过 IM Provider 适配器模式抹平多引擎协议差异，在 before/after-send 钩子里插入反导流 + 异步扣费 + 落库 + AI 路由业务规则，并用双层 Redis ZSet + PG 表维护在线状态供 match-service 的 DH 模拟计划使用。

## 重点数字（背下来能应付 80% 追问）

| 数字 | 含义 | 出现位置 |
|------|------|---------|
| **9** | im-service 对外 gRPC 接口数 | `im.proto` `ImService` |
| **6** | 单条聊天消息扣金币数 | `im.message.charge.coin-cost` |
| **2** | CoinChargeDispatcher 异步线程数 | `Executors.newFixedThreadPool(2)` |
| **2 / 5** | typing onset-delay 随机范围(秒) | `im.typing.onset-delay-{min,max}-ms` |
| **3s** | typing 续命间隔 | `im.typing.refresh-interval-ms` |
| **30 min** | LiveKit JWT TTL | `generateCallToken` |
| **26h** | sweep 孤儿会话阈值 | `im.presence.sweep.max-online-hours` |
| **30 min** | PresenceSweepJob cron 间隔 | `0 */30 * * * *` |
| **5 min** | ShedLock `lockAtMostFor` | `@SchedulerLock` |
| **5000 / 50000** | ListOnlineUsers 默认/上限 limit | proto 默认值 |
| **4 类** | ImEvent sealed permits | MessageBefore / MessageSent / UserOnline / UserOffline |
| **4 类** | 消息路由 route_type | BH_BH / BH_DH / DH_BH / DH_DH |
| **5 类** | ContactInfoDetector 检测 | Instagram / Facebook / WhatsApp / Telegram / US Phone |
| **5000** | ImGrpcService TokenResult 默认 | OpenIM `expireTimeSeconds` |

## im-service 的关键决策点

### 决策 1：before-send 钩子在 IM 引擎而非 App
- **App 端**：可被反编译绕过 → 反导流失效
- **IM 引擎钩子**：服务端执行不可绕过
- → 业务规则（反导流 + 扣费）必须在服务端 before-send 钩子执行

### 决策 2：异步扣费 + messageId 幂等
- **同步**：扣成功才放行；payment 抖动 → 消息发不出去 → UX 差
- **异步**：预检通过 → 放行 → 后台真扣；payment 抖动 → 损失 ≤ 6 金币
- → 默认 async=true，messageId 作幂等键防重复扣

### 决策 3：双层在线状态
- **只用 Redis**：Redis 抖动丢数据 → DH 计划取不到
- **只用 PG**：高频 ListOnlineUsers range 扫描慢（PG vs Redis ZSet 10x+）
- → Redis ZSet 撑高频读 + PG 持久化历史 + sweep 兜底

### 决策 4：IM Provider 适配器
- **直接绑死 OpenIM**：切换引擎 → 改一堆 if/else
- **适配器 + sealed interface**：新增 adaptor 即可，业务代码零改动
- → 当前 OpenImAdaptor；未来可加 TencentImAdaptor 灰度

### 决策 5：ShedLock 替代 Redisson
- **ShedLock + PG**：任务少 + 已有 PG schema → 标准库 + JDBC 持久化
- **Redisson + Redis**：锁粒度细 + 高频 → 业务级锁
- → im-service 选 ShedLock（只有 1 个 sweep 任务）

### 决策 6：service 间只用 gRPC
- **CLAUDE.md 红线 #3**：禁止 HTTP/Feign/RestTemplate 代替 gRPC
- **OpenIM/LiveKit HTTP 是例外**：外部引擎，不在 dating app 微服务体系内
- → 业务服务（user/payment）走 gRPC；外部引擎（OpenIM/LiveKit）走 HTTP

## im-service 的关键边界

| 边界 | im-service 的角色 |
|------|------------------|
| user-service | 调用方（isDigitalHuman gRPC） |
| payment-service | 调用方（getBalance + consumeCoins gRPC） |
| match-service | 被调用方（EnsureConversation / SendSystemMessage / TriggerDhOpening / ListOnlineUsers） |
| OpenIM | **唯一业务封装**（HTTP REST API + callback 钩子） |
| LiveKit | **唯一业务封装**（JWT 签发） |
| App | **不接长连接**；App → OpenIM WS；OpenIM callback → im-service |

## 学习路径建议

1. 先看 `prd.md` 把 12 个功能点和数据流转吃透（什么 before-send / after-send / route_type / 在线状态双层）
2. 再看 `knowledge.md` 把每个技术决策的 why 理解（为什么异步扣费、为什么适配器模式、为什么双层在线）
3. 最后看 `interview-qa.md` 整理话术，模拟被问 Q3（before-send 为什么不在 App）/ Q4（异步扣费幂等）/ Q5（双层在线）三个最高频问题

## 已知 gap（面试时主动提及）

1. **`ImGrpcService.sendMessage` / `EnsureConversation` / `TriggerDhOpening` / `SendSystemMessage` 是 TODO 占位** — 当前生产 match-service 绕开 im-service 直接调 OpenIM，是技术债
2. **`AiReplyService.triggerAiReply` 是 placeholder** — `Thread.sleep(2000) + 单条消息`，未做 typing 续命 + 分段发送
3. **`UserServiceClient.isDigitalHuman` 本地缓存无 TTL** — 依赖 BH/DH 类型不变的假设；如果未来可变需加 Nacos invalidate
4. **`ContactInfoDetector` 正则可能误伤** — 美号正则会误伤中国手机号/微信号；运营反馈调正则
5. **ListOnlineUsers / ListRecentOfflineUsers 未过滤 DH** — 当前实现不过滤 DH user_id，理论上 match-service 端有过滤；已知边界

## 关联文档

- `doc/course/2026-07-23-match-service/knowledge.md` 知识点四（DH 模拟计划的真实性约束）— 依赖 im-service 在线状态
- `doc/course/2026-07-23-match-service/knowledge.md` 知识点九（match-service 严守边界）— match-service 调 im-service gRPC
- `doc/course/2026-07-25-payment-service/knowledge.md` 幂等性设计 — 异步扣费 messageId 幂等键设计
- `doc/course/2026-07-19-grpc-protobuf/knowledge.md` gRPC 设计规范 — im.proto 包坐标 / version 管理