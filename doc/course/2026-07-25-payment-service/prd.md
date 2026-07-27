# payment-service 功能设计文档（类 PRD）

## 1. 模块概述

### 1.1 业务定位

payment-service 位于约会业务的价值闭环中心，统一管理：

- 商品和支付订单：把内部商品映射成第三方支付订单。
- 付费金币：充值获得、消费扣减、余额查询和流水审计。
- 订阅权益：FREE/WEEKLY/MONTHLY/YEARLY 档位及有效期。
- 法币钱包/提现：已建表和接口骨架，业务实现暂未完成。

其他服务不能直接访问 payment-service 的数据库，应通过 gRPC 调用余额、扣币和订阅能力。

### 1.2 用户角色

| 角色 | 使用能力 |
|---|---|
| App/Web 用户 | 查看商品、创建订单、跳转 PayPal、确认支付、查看金币和订阅 |
| match-service | 查询订阅、消费金币，例如 SuperHi |
| 管理/运营 | 未来可通过发币、激活订阅和提现审核接口操作 |
| PayPal | 创建外部订单、capture、异步 Webhook 通知 |

## 2. 功能清单

| 编号 | 功能 | 入口 | 当前状态 | 关键规则 |
|---|---|---|---|---|
| F001 | 商品列表 | `GET /v1/payments/products` | 已实现 | 商品当前由代码初始化 |
| F002 | 创建订单 | REST `/orders`、RPC `CreateOrder` | 已实现 PayPal | 先校验商品，再创建外部订单并落内部订单 |
| F003 | 主动确认支付 | REST `/verify`、RPC `VerifyPayment` | 已实现 PayPal | capture 成功后进入发奖流程 |
| F004 | PayPal Webhook | `POST /v1/payments/webhook/paypal` | 已实现基础链路 | `PAYMENT.CAPTURE.COMPLETED` 触发支付和发奖 |
| F005 | 查询订单 | `GET /v1/payments/orders/{orderId}` | 已实现 | 返回订单状态与第三方交易号等信息 |
| F006 | 查询金币 | REST `/v1/coins/balance`、RPC `GetCoins` | 已实现 | 返回免费币、付费币和总余额 |
| F007 | 增加免费币 | REST `/add`、RPC `AddCoins` | 已实现 | 幂等键 + 乐观锁重试 |
| F008 | 增加付费币 | REST `/addPaid`、RPC `AddPaidCoins` | 已实现 | 充值发奖使用付费币账户 |
| F009 | 消费金币 | REST `/consume`、RPC `ConsumeCoins` | 已实现 | 先扣免费币，余额不足才扣付费币 |
| F010 | 金币流水 | REST `/ledger`、RPC `GetCoinLedger` | REST 已实现 | REST 分页完成，gRPC 仍返回空分页占位 |
| F011 | 查询订阅 | REST `/v1/subscription`、RPC `GetSubscription` | 已实现 | 过期或 FREE 统一按未激活处理 |
| F012 | 激活订阅 | RPC `ActivateSubscription` | 已实现 | 未过期顺延时长，档位只升不降 |
| F013 | 提现 | Withdraw Controller/Service | 占位 | 表结构已建，绑定账户、申请和历史未实现 |
| F014 | 多支付通道 | proto 已定义 | 未实现 | Apple/Google/Stripe 当前返回不支持或未落地 |

## 3. 功能详情

### 3.1 商品与创建支付订单

#### 业务规则

1. 商品必须存在，否则返回 `PRODUCT_NOT_FOUND`。
2. 当前只处理 `PAYPAL`，其他通道返回未支持错误。
3. 内部业务订单号与 PayPal 外部订单号分离保存。
4. 订单初始状态为 `INIT`，表示已下单但尚未确认收款。
5. 商品既可以是金币商品，也可以是“订阅 + 付费币”组合商品。

当前内置商品：

| 商品 | 价格 | 付费币 | 订阅 |
|---|---:|---:|---|
| `1` ~ `6` | USD 0.99 ~ 99.99 | 100 ~ 13000 | 无 |
| `sub-weekly` | USD 9.99 | 1000 | WEEKLY/7 天 |
| `sub-monthly` | USD 29.99 | 3000 | MONTHLY/30 天 |
| `sub-yearly` | USD 79.99 | 8000 | YEARLY/365 天 |

#### 端到端流程

```text
客户端请求商品列表
    ↓
客户端提交 productId + channel + returnUrl
    ↓
PaymentService 校验商品
    ↓
生成内部 orderId（P + 时间 + 随机段）
    ↓
PaypalExecutor 获取/复用 access token
    ↓
调用 PayPal Create Order，返回 extOrderId 和 approval link
    ↓
写 payment_orders：INIT + 商品快照 + 外部订单号
    ↓
返回 checkoutUrl，客户端跳转支付
```

#### REST 接口

`POST /v1/payments/orders`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `userId` | Long | 是 | 业务用户 ID |
| `productId` | String | 是 | 内部商品 ID |
| `channel` | String | 否 | 默认 `PAYPAL` |
| `returnUrl` | String | 否 | 支付后跳转地址 |

返回重点字段：`orderId`、`status=INIT`、`extOrderId`、`checkoutUrl`。

### 3.2 支付确认与发奖

#### 状态机

```text
INIT --PayPal capture 成功--> PAID --发放金币/订阅成功--> GRANTED
  │                              │
  └--capture 失败--------------> FAILED

PAID/GRANTED 再次确认：直接返回当前状态，不重复发奖
```

`PAID` 和 `GRANTED` 的拆分很重要：第三方已经确认收款，不代表本地权益已经成功落库。`GRANTED` 是业务补偿和幂等的锚点。

#### 主动确认流程

1. 按 `orderId` 查询内部订单。
2. 如果状态是 `PAID` 或 `GRANTED`，直接返回，避免重复 capture/发奖。
3. 使用传入的外部订单号；未传时回退到订单保存的 `extTransactionId`。
4. 调 PayPal capture。
5. capture 成功：订单推进为 `PAID`，执行 `grantReward`。
6. capture 失败：推进为 `FAILED` 并抛出业务异常。
7. 发奖时根据商品判断是否激活订阅，再增加付费币，最后把订单置为 `GRANTED`。

#### Webhook 流程

PayPal 回调入口解析 `event_type` 和 `resource`：

- `resource.custom_id` 作为内部 `orderId`。
- `resource.id` 作为外部交易号。
- `PAYMENT.CAPTURE.COMPLETED`：推进 `PAID` 并发奖。
- `CHECKOUT.ORDER.APPROVED`：尝试兜底自动 capture。
- 其他事件：记录日志，不改变订单。

生产补强项：Webhook 必须验签、校验事件和订单归属、基于事件 ID 去重，并避免把原始 payload 写入普通日志。

### 3.3 金币账户与流水

#### 账户模型

每个用户一行 `coin_accounts`：

- `balance`：免费币。
- `paid_balance`：付费币。
- `version`：乐观锁版本。

每次变更同时写 `coin_ledger`，保存变更前后余额和幂等键，形成 append-only 审计记录。

#### 增加金币

```text
请求 amount + idempotencyKey
    ↓
查询(userId, key)是否已有流水
    ├─ 有：返回已有 balance_after
    └─ 无
        ↓
读取账户，计算新余额
        ↓
插入流水（数据库唯一索引兜底并发重复请求）
        ↓
带乐观锁更新账户
        ├─ 冲突：最多重试 3 次
        └─ 成功：返回新余额
```

`AddPaidCoins` 过程相同，但读写 `paid_balance` 和 `paid_balance_after`。

#### 消费金币

1. 校验 `amount > 0`。
2. 先按 `(userId, key)` 查询历史流水，命中则返回历史结果。
3. 读取免费币和付费币总额。
4. 总额不足返回 `3001 INSUFFICIENT_COINS`，不写流水。
5. `freeTake = min(amount, freeBalance)`。
6. `paidTake = amount - freeTake`。
7. 同一事务写 EXPENSE 流水并更新双余额。
8. 乐观锁冲突最多重试 3 次。

### 3.4 订阅权益

订阅档位为 `FREE=1`、`WEEKLY=2`、`MONTHLY=3`、`YEARLY=4`。

- 无记录、到期、档位不高于 FREE：返回 FREE 且 `active=false`。
- 首次激活：从当前时间开始增加时长。
- 当前未过期：新档位与旧档位取高值，时长在当前到期时间上顺延。
- 当前已过期：切换到新档位，从当前时间重新计算到期时间。
- 数据库部分唯一索引保证每个用户最多一条未删除订阅。

### 3.5 提现预留

已有 `user_wallets`、`user_wallet_entries`、`withdraw_records` 表和 `WithdrawService` 接口，但当前 `bindAccount`、`apply`、`getHistory` 返回 `NOT_IMPLEMENTED`。未来完整流程应包含：绑定账户、余额校验、冻结余额、审核、打款、成功/失败解冻或扣账、钱包流水审计和幂等处理。

## 4. 接口设计

### 4.1 gRPC 服务

定义在 `proto/payment/payment.proto` 的 `PaymentService`：

- 支付：`CreateOrder`、`VerifyPayment`。
- 余额兼容：`GetBalance`、`GetCoins`。
- 金币：`AddCoins`、`AddPaidCoins`、`ConsumeCoins`、`GetCoinLedger`。
- 订阅：`GetSubscription`、`ActivateSubscription`。
- 兼容遗留：`PurchaseCoins`。

match-service 重点依赖：

- `GetSubscription`：判断免费/付费权益。
- `ConsumeCoins`：消费 SuperHi 等付费能力，调用方必须生成稳定幂等键。

### 4.2 错误码

| 区间 | 领域 | 示例 |
|---|---|---|
| `2001~2099` | PayPal | 创建、capture、验签、Webhook |
| `3001~3099` | 金币 | 余额不足、账户问题 |
| `4001~4099` | 参数/订单 | 参数缺失、订单/商品不存在 |
| `5001~5099` | 提现 | 账户未绑定、余额不足 |
| `9999` | 占位 | 通道或接口未实现 |

## 5. 数据模型与迁移

| 表 | 用途 | 关键约束 |
|---|---|---|
| `payment_orders` | 支付订单 | `order_id` 唯一，状态 CHECK |
| `coin_accounts` | 双金币余额 | `user_id` 主键，余额非负，版本号 |
| `coin_ledger` | 金币流水 | `(user_id,idempotency_key)` 部分唯一 |
| `user_subscription` | 当前订阅 | `user_id` 活跃记录部分唯一 |
| `user_wallets` | 法币钱包 | 余额/冻结余额非负，版本号 |
| `user_wallet_entries` | 钱包审计流水 | append-only |
| `withdraw_records` | 提现状态 | `withdraw_no` 唯一，状态 CHECK |

全部迁移使用 `TIMESTAMPTZ`，服务连接初始化设置 UTC。迁移顺序为 V1 钱包/订单、V2 提现、V3 免费币、V4 付费币、V5 订单回调增强、V6 订阅和幂等键。

## 6. 监控与验收指标

建议围绕以下指标建立监控：

- `payment.order.create.success/failure`：创建订单成功率。
- `payment.paypal.capture.latency/failure`：第三方确认延迟和失败率。
- `payment.webhook.received/duplicate/invalid_signature`：回调质量。
- `payment.reward.grant.success/failure`：发奖成功率及 PAID 堆积量。
- `payment.coin.consume.success/insufficient/retry_exhausted`：扣币成功、余额不足和并发冲突。
- `payment.subscription.activate.success`：订阅激活与续期。
- `payment.withdraw.pending/failed`：提现待处理和失败量。

## 7. 关键边界情况

- 重复点击确认支付：已支付或已发奖直接返回。
- Webhook 与主动确认同时到达：必须依赖数据库状态条件更新和发奖幂等，避免双发。
- 同一幂等键并发请求：数据库唯一索引是最终兜底。
- 账户并发扣币：乐观锁失败后有限重试，重试耗尽返回错误。
- 余额不足：不产生 EXPENSE 流水，不改变余额。
- 订阅过期后续费：从 now 计算，不把过期时间继续顺延。
- PayPal 不可用：订单不能进入 PAID；应保留可重试/人工补偿路径。
- 服务重启或重复回调：不能依赖进程内状态完成发奖，数据库状态必须是权威依据。
