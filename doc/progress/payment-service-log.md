# payment-service 进度日志

## 2026-07-18 (Session #1)

**目标**: 完成 payment-service 基础骨架 + 核心业务实现

**完成**:
- [x] Flyway 数据库迁移脚本 (V1-V6)
  - V1: 钱包和支付订单表
  - V2: 提现记录表
  - V3: 金币账户基础版
  - V4: 付费币分离
  - V5: 订单 GRANTED 状态增强
  - V6: 订阅模块 + 幂等键
- [x] Entity 实体类 (7个)
  - PaymentOrderEntity, CoinAccountEntity, CoinLedgerEntity
  - UserSubscriptionEntity, UserWalletEntity, WalletEntryEntity, WithdrawRecordEntity
- [x] Mapper 接口类 (7个)
- [x] Manager 数据访问编排类 (7个)
- [x] Service 业务服务类 (完整实现)
  - CoinService: 免费/付费双账户，扣减先免费后付费，幂等
  - SubscriptionService: FREE/WEEKLY/MONTHLY/YEARLY 档位，只升不降+时长顺延
  - PaymentService: PayPal 完整链路
  - WithdrawService: 占位实现
- [x] gRPC 服务实现 (PaymentGrpcService + PaymentGrpcServer)
- [x] Controller REST 接口
  - PaymentController, WebhookController, CoinController
  - SubscriptionController, WithdrawController, HealthController
- [x] PayPalExecutor 支付执行器
- [x] VO/DTO 类和异常处理
- [x] Application 启动类和配置

**遗留**:
- [x] proto 模块编译验证通过
- [x] payment-service 编译验证通过
- [ ] Nacos 配置还未填写敏感信息
- [ ] 提现模块业务逻辑待完善
- [ ] IAP (Apple/Google) 通道待实现
- [ ] GetBalance RPC 接口待完善

**AI 行为备注**:
- 严格遵循 `.cursorrules` 目录分层约定
- 所有方法添加 Javadoc
- 敏感配置使用 `${ENV}` 占位
