# payment-service 学习笔记

> 学习日期：2026-07-25
> 学习范围：payment-service 的支付订单、PayPal、金币账户、订阅、gRPC/REST 接口、提现预留设计

## 本次学习内容

- [业务功能设计](./prd.md) - 功能清单、业务流程、数据模型、接口与状态机
- [核心知识点](./knowledge.md) - 支付发奖幂等、金币并发控制、双余额模型、订阅续期与第三方支付适配
- [面试问答（通用版）](./interview-qa.md) - 项目讲解话术、状态机、第三方支付、订阅、gRPC 与场景设计
- [面试问答（可靠性专版）](./interview-qa-reliability.md) - 60+ 高频问题：以"每笔交易怎么保证可靠"为主轴，串联状态机、幂等、并发、Webhook、安全、订阅、可观测性、场景设计

## 学习目标

- [ ] 能讲清支付订单从创建、第三方支付到发放权益的完整链路
- [ ] 掌握 `INIT → PAID → GRANTED` 状态机，以及为什么要把“支付成功”和“权益发放”拆开
- [ ] 掌握免费币/付费币双账户、先免费后付费扣减和流水审计
- [ ] 掌握基于数据库唯一索引和乐观锁的幂等、并发控制方案
- [ ] 能解释订阅档位、到期判断、续期顺延和只升不降规则
- [ ] 能识别当前实现中的安全与可靠性缺口，并提出演进方案

## 一句话定位

> payment-service 是约会应用的价值账户中心：负责商品与支付订单、PayPal 交易确认、付费币发放、金币消费、订阅权益管理，并通过 gRPC 为其他业务服务提供余额、扣币和订阅查询能力。

## 代码地图

```text
dating-server/payment-service/src/main/java/com/dating/payment/
├── controller/       # Payment/Coin/Subscription/Webhook/Withdraw REST 入口
├── grpc/             # PaymentGrpcServer + PaymentGrpcService
├── service/          # PaymentService/CoinService/SubscriptionService/WithdrawService
├── service/impl/     # 业务实现
├── manager/          # 单表数据访问编排、分页、乐观锁与幂等查询
├── mapper/           # MyBatis-Plus Mapper，一张表一个
├── entity/           # 订单、金币账户/流水、订阅、钱包实体
├── executor/         # PayPal REST API 适配器
├── constant/         # 状态、通道、错误码、Redis key 常量
└── vo/               # 接口返回对象
```

## 推荐学习顺序

1. 先看 [prd.md](./prd.md)，建立业务全貌，重点看支付和金币两条链路。
2. 再看 [knowledge.md](./knowledge.md)，理解每个技术选择解决的并发、一致性和审计问题。
3. 最后看 [interview-qa.md](./interview-qa.md)，用“场景—问题—方案—权衡—结果”的方式复述。
4. 学习时同步打开 `PaymentServiceImpl`、`CoinServiceImpl`、`SubscriptionServiceImpl` 和 `payment.proto` 对照。

## 关键代码证据

- 支付主流程：`dating-server/payment-service/src/main/java/com/dating/payment/service/impl/PaymentServiceImpl.java`
- 金币并发与幂等：`dating-server/payment-service/src/main/java/com/dating/payment/service/impl/CoinServiceImpl.java`
- 订阅续期：`dating-server/payment-service/src/main/java/com/dating/payment/service/impl/SubscriptionServiceImpl.java`
- RPC 契约：`proto/payment/payment.proto`
- 数据演进：`dating-server/payment-service/src/main/resources/db/migration/V1__init_wallet_and_payment.sql` 至 `V6__init_subscription_and_idempotency.sql`

## 当前实现边界

- 已实现：PayPal 下单、主动 capture、Webhook 事件处理、金币增减/消费、订阅查询与激活、REST 和 gRPC 骨架。
- 已预留但未完成：Apple IAP、Google Billing、Stripe、完整提现业务、gRPC 金币流水分页。
- 学习时要区分“当前代码已经做到什么”和“生产系统还需要补什么”。
