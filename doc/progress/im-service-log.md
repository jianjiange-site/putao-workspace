# im-service 开发进度日志

## 2026-07-17 (Session #1)

**目标**: 完成 im-service 基础骨架和服务实现

### 完成内容

**基础架构**
- [x] 完善 pom.xml (proto 依赖、gRPC、Redis、Redisson、Flyway、ShedLock、JWT)
- [x] 完善 application.yml (端口配置、Nacos、Redis、OpenIM、LiveKit、RocketMQ)
- [x] ImApplication 启动类
- [x] RedisConfig
- [x] RedissonConfig
- [x] GrpcClientConfig (user-service、payment-service)

**Proto 定义**
- [x] 扩展 im.proto (添加 OnRawCallback、GenerateCallToken、ListOnlineUsers、ListRecentOfflineUsers RPC)
- [x] 补充 user.proto: GetUserType RPC (BH/DH 查询)
- [x] 补充 payment.proto: ConsumeCoins RPC (聊天扣费)
- [x] 创建 payment-proto/pom.xml

**Entity 和 Mapper**
- [x] ChatMessageEntity (消息流水)
- [x] UserOnlineSessionEntity (在线会话)
- [x] ChatMessageMapper
- [x] UserOnlineSessionMapper

**核心组件**
- [x] OpenImAdaptor (OpenIM 回调解析)
- [x] ImProviderAdaptorManager (适配器分发)
- [x] ImProviderAdaptor 接口
- [x] OpenImApiClient (OpenIM REST API)
- [x] UserServiceClient (调用 user-service BH/DH 查询)
- [x] PaymentServiceClient (调用 payment-service 扣费)

**服务实现**
- [x] CallbackService (回调总分发)
- [x] BeforeSendHandler (反导流 + 扣费预检)
- [x] CoinChargeDispatcher (异步扣费调度)
- [x] MessageSentHandler (落库 + AI 路由)
- [x] AiReplyService (AI 回复编排 - 占位实现)
- [x] ContactInfoDetector (联系方式检测)
- [x] PresenceService (在线状态管理)
- [x] TokenService (IM Token + LiveKit Token)
- [x] NotificationService (业务通知下发)
- [x] ImGrpcService (gRPC 服务端实现)

**定时任务**
- [x] PresenceSweepJob (孤儿会话清扫)

**常量定义**
- [x] ImRedisKey (Redis Key 规范: putao:im:presence:online)
- [x] MessageType (消息类型枚举)
- [x] RouteType (路由类型枚举: BH_BH/BH_DH/DH_BH/DH_DH)
- [x] NotificationKeys (通知 Key 常量)
- [x] ImErrorCode (错误码)

**数据库**
- [x] Flyway V1__init_im_tables.sql (chat_messages + user_online_session)

### 遗留事项

- [ ] 尚未实现的 AI 回复完整流程 (ai-chat 调用、分段发送、typing 续命)
- [ ] 需要先 `mvn install` proto 模块才能编译 service
- [ ] Nacos 配置需要填写 OpenIM 凭据、LiveKit 凭据等

### AI 行为备注

- im-service 参考 post-service 的配置模式
- Proto 定义扩展了 mobile-gateway 现有接口，新增了回调处理和在线状态查询接口
- 消息表设计采用 chat_messages 表结构
- 创建了 payment-proto 目录和 pom.xml，补充了 ConsumeCoins RPC
