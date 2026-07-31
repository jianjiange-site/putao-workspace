# payment-service 业务流程详解

> 这是代码学习主文档。审阅基线为 2026-07-30 当前工作区，包含尚未提交的可靠性改动。文中“当前实现”描述工作区源码，不把 TODO 或演进方案写成已完成能力。

## 1. 阅读前先看：当前代码能否运行

当前业务意图可以完整追踪，但工作区存在两个启动前阻断：

1. `PaymentOrderManager.findByOrderId` 在 `return` 后缺少方法结束括号，导致 `findByOrderIdForUpdate` 和 `findByExternalTransaction` 被写进另一个方法体，Java 源码无法编译。
2. Flyway 扫描目录同时存在两个 `V1__*.sql`，会产生重复版本；合并版文件的注释不会让 Flyway自动忽略它。

本次尝试执行 Maven 编译时，运行环境又因本地仓库路径 `C:\.m2\repository` 不可写而在编译前退出。因此本文的“编译阻断”来自源码和迁移静态证据，不声称完成了全量构建或集成测试。

## 2. 完整入口与维护流清单

### 2.1 REST 入口

| 入口 | 对应流程 | 状态 |
|---|---|---|
| `GET /health` | 健康探针 | 已实现，只返回 `OK` |
| `GET /v1/payments/products` | 商品列表 | 已实现 |
| `POST /v1/payments/orders` | 创建支付订单 | PayPal 已实现 |
| `POST /v1/payments/verify` | 主动 capture 与发奖 | 部分实现 |
| `GET /v1/payments/orders/{orderId}` | 查询订单 | 已实现，缺归属鉴权 |
| `POST /v1/payments/webhook/paypal` | PayPal 验签、确认与发奖 | 部分实现 |
| `POST /v1/coins/balance` | 查询双余额 | 已实现 |
| `POST /v1/coins/add` | 增加免费币 | 已实现 |
| `POST /v1/coins/addPaid` | 增加付费币 | 已实现 |
| `POST /v1/coins/consume` | 消费金币 | 已实现 |
| `GET /v1/coins/ledger` | 金币流水分页 | 已实现 |
| `POST /v1/subscription` | 查询订阅 | 已实现 |
| `GET /v1/withdraw/balance` | 查询法币钱包余额 | 只读已实现 |
| `POST /v1/withdraw/accounts` | 绑定提现账户 | 占位 |
| `POST /v1/withdraw/request` | 申请提现 | 占位 |
| `GET /v1/withdraw/history` | 提现历史 | 占位 |

### 2.2 gRPC 入口

`PaymentGrpcServer` 实现 proto 服务，所有方法委托给 `PaymentGrpcService`。

| RPC | 落地业务 | 状态 |
|---|---|---|
| `CreateOrder` | 创建订单 | PayPal 已实现 |
| `VerifyPayment` | 主动确认 | 部分实现 |
| `GetBalance` / `GetCoins` | 查询双余额 | 已实现，二者相同 |
| `AddCoins` | 增加免费币 | 已实现 |
| `AddPaidCoins` | 增加付费币 | 已实现 |
| `ConsumeCoins` | 消费金币 | 已实现 |
| `GetCoinLedger` | 查询金币流水 | 占位空页 |
| `GetSubscription` | 查询订阅 | 已实现 |
| `ActivateSubscription` | 激活/续期订阅 | 部分实现 |
| `PurchaseCoins` | 遗留购买金币 | 占位空结果 |

### 2.3 后台与异步机制

当前服务没有：

- `@Scheduled` 定时任务；
- MQ producer/consumer；
- `@Async` 后台发奖；
- `PAID` 补偿扫描；
- 退款、对账或提现处理 worker。

PayPal Webhook 是外部异步入口，但收到后仍在 HTTP 请求线程中同步验签、加锁、发奖。

## 3. 商品与支付生命周期

### 3.1 获取商品列表

**业务目标**：让客户端获得可购买的金币包和订阅包。

**调用链**

```text
PaymentController.getProducts
  → PaymentServiceImpl.getProducts
  → ProductInfoServiceImpl.getAllProducts
  → 内存 LinkedHashMap
  → Result.ok
```

**执行过程**

1. 服务启动时，`ProductInfoServiceImpl.init` 通过 `@PostConstruct` 创建六个金币包和三个订阅包。
2. Controller 接收列表请求，不做用户、地区、币种或渠道校验。
3. `getAllProducts` 按 `LinkedHashMap` 插入顺序返回对象列表。
4. 过程不访问数据库、Redis、PayPal 或其他服务。
5. 客户端看到的是当前实例内商品定义；多实例发布不同版本时，列表和发奖定义可能不一致。

**关键代码**

```java
return products.values().stream().toList();
```

**一致性**：商品没有版本号或持久化快照，订单只保存商品编号、金额和币种，发奖会再次读取当前内存定义。

### 3.2 创建 PayPal 订单

**业务目标**：把一个内部商品转换成可支付的 PayPal 订单。

**触发入口**：REST `PaymentController.createOrder` 或 gRPC `PaymentGrpcService.createOrder`。

**调用链**

```text
Controller / PaymentGrpcServer
  → PaymentGrpcService（gRPC 路径）
  → PaymentServiceImpl.createOrder
  → ProductInfoServiceImpl.getProduct
  → PaymentServiceImpl.createPayPalOrder
  → PaypalExecutor.createOrder
      → PaypalExecutor.getAccessToken
      → PayPal OAuth / Checkout REST
  → PaymentOrderManager.save
  → PaymentOrderMapper.insert
  → payment_orders
```

**执行过程**

1. `createOrder` 读取商品编号并调用 `getProduct`。不存在时抛 `PRODUCT_NOT_FOUND`。
2. 服务用当前毫秒时间和六位随机数生成内部订单号：

   ```java
   "P" + System.currentTimeMillis() + sixDigitRandom
   ```

   数据库唯一索引是最终碰撞保护，但代码没有捕获碰撞后重试。
3. 通道必须精确等于字符串 `PAYPAL`。REST 默认给出 PayPal；gRPC 未指定枚举会映射为空字符串并进入未支持分支。
4. `PaypalExecutor` 检查 client id/secret。未配置时不会阻止服务启动，但本次调用失败。
5. Access token 保存在当前 JVM，`getAccessToken` 使用 `synchronized` 防止单实例并发刷新；过期判断预留 60 秒，而写入 `tokenExpiresAt` 时又提前 120 秒。
6. Executor 用 ObjectMapper 构造 PayPal 请求，写入 `reference_id/custom_id/invoice_id = 内部订单号`、USD 金额、商品名和回跳地址。
7. 请求头带 `PayPal-Request-Id = 内部订单号`，使同一个内部订单号的 PayPal 创建调用具备外部幂等语义。
8. PayPal 返回后，Executor提取外部订单号和 `approve` 链接。非 `CREATED/PENDING` 状态只记告警，仍继续返回。
9. 外部订单创建成功后才插入 `payment_orders`，状态为 `INIT`，并保存金额、币种、通道、外部订单号、通知初始状态和回跳地址。
10. 返回内部订单号、`INIT`、外部订单号和跳转链接。

**事务与失败**

- `createOrder` 标有 `@Transactional`，外部 HTTP 发生在本地插入之前。
- PayPal 成功、本地插入失败时，本地事务回滚，但 PayPal 外部订单保留。
- 客户端重试会生成新内部订单号，因而可能再建一笔外部订单。
- `RestTemplate` 未配置连接/读取超时、重试或熔断。
- return URL 没有白名单校验。

### 3.3 主动确认支付

**业务目标**：用户从 PayPal 返回后立即确认收款并领取权益。

**触发入口**：REST `verifyPayment` 或 gRPC `VerifyPayment`。

**调用链**

```text
Controller / PaymentGrpcServer
  → PaymentGrpcService.verifyPayment（gRPC 路径）
  → PaymentServiceImpl.verifyPayment [事务]
  → PaymentOrderManager.findByOrderIdForUpdate
  → PaymentOrderMapper.findByOrderIdForUpdate
  → PaypalExecutor.captureOrder / getOrderDetails
  → validateCapture
  → advanceToPaid
  → grantReward
      → SubscriptionServiceImpl.activateSubscription（订阅商品）
      → CoinServiceImpl.addPaidCoins
      → advance PAID → GRANTED
```

**执行过程**

1. `verifyPayment` 开启本地事务，并按内部订单号执行 `SELECT ... FOR UPDATE`。
2. 找不到订单，或请求用户编号为空/与订单用户不一致，都返回“订单不存在”，避免泄露订单归属。
3. 若状态已经是 `GRANTED`，不再 capture 或发奖，最后读取当前订单并返回。
4. 若状态是 `PAID`，跳过 capture，直接进入 `grantReward`。这是人工或未来补偿可以复用的本地恢复入口，但当前没有自动扫描任务。
5. 其他非 `GRANTED` 的 PayPal 订单调用 `PaypalExecutor.captureOrder`。当前实现忽略接口传入的 `extOrderId`，始终使用数据库保存的外部订单号。
6. PayPal 返回 `COMPLETED` 时解析外部订单号、内部 merchant order id、金额、币种和商户号。
7. 如果 capture 返回 `ORDER_ALREADY_CAPTURED`，Executor 再查询 PayPal 订单详情，并要求状态为 `COMPLETED`，而不是直接假设成功。
8. `validateCapture` 逐项比较本地外部订单号、内部订单号、金额和币种；配置了 merchant id 时 Executor 也会核对收款商户。
9. 校验成功后，订单仅在当前状态是 `INIT` 或 `FAILED` 时条件更新为 `PAID`。
10. `grantReward` 再次锁定同一订单。由于同一事务、同一连接持锁，这是可重入的数据库读取。
11. `grantReward` 要求状态为 `PAID`；订阅商品先调用订阅激活，再用 `order:<orderId>` 增加付费币。
12. 所有权益成功后，订单以 `WHERE status = PAID` 条件更新为 `GRANTED`；影响行数不是 1 就抛异常。
13. 方法重新查询订单，把最终状态返回给客户端。

**关键状态 SQL**

```text
INIT/FAILED --条件更新--> PAID
PAID        --条件更新--> GRANTED
```

**事务与失败**

- 订单锁、PayPal capture、本地订阅、金币流水、金币余额和订单状态处于同一 Spring 事务上下文。
- PayPal capture 已成功后，本地任何异常都会回滚本地数据库，但不能回滚 PayPal。
- capture 失败分支调用 `advanceToFailed` 后重新抛出运行时异常；由于仍在同一事务，`FAILED` 更新随事务一起回滚，订单通常仍是 `INIT`。
- 订阅商品当前传入来源 `"PayPal"`，但数据库 CHECK 不允许该值。因此按仓库迁移建库时，订阅写入会失败，整笔本地发奖回滚。
- 普通金币包不经过订阅表，可避开来源约束，但仍受当前源码编译阻断影响。

### 3.4 PayPal Webhook

**业务目标**：客户端没有主动确认时，仍能根据 PayPal 通知完成收款确认与发奖。

**调用链**

```text
WebhookController.handlePayPalWebhook
  → PaypalExecutor.verifyWebhookSignature
      → PayPal verify-webhook-signature REST
  → ObjectMapper.readTree
  → PaymentServiceImpl.handlePayPalWebhook [事务]
  → locate order
  → SELECT payment_orders FOR UPDATE
  → validateWebhookOrder
  → event-specific branch
```

**公共前置过程**

1. Controller 只记录 transmission id，不再把完整 payload 打到 INFO。
2. `verifyWebhookSignature` 要求配置 PayPal 凭据和 webhook id，并要求五个签名头完整。
3. Executor 把原始事件 JSON 与签名信息提交给 PayPal 验签接口；只有 `verification_status=SUCCESS` 才继续。
4. Controller 解析事件类型、`custom_id` 和 `supplementary_data.related_ids.order_id`。对于 `CHECKOUT.ORDER.APPROVED`，外部订单号回退到资源自身 `id`。
5. Service 优先用内部订单号定位；没有时按“PAYPAL + 外部订单号”查询，再按内部订单号锁行。
6. `validateWebhookOrder` 检查外部订单号、payload 中可选的 `custom_id`，以及资源中存在时的金额和币种。

#### 分支 A：`PAYMENT.CAPTURE.COMPLETED`

1. 已是 `GRANTED` 时直接跳过。
2. 否则把 `INIT/FAILED` 条件推进到 `PAID`。
3. 调用与主动确认相同的 `grantReward`。
4. 本地全部提交后 Controller 返回业务成功。

注意：此分支信任已经通过远程验签的 `COMPLETED` 事件，不再调用 capture 查询。

#### 分支 B：`CHECKOUT.ORDER.APPROVED`

1. 使用数据库保存的 PayPal order id 主动 capture。
2. 验证 capture 的订单号、金额和币种。
3. 推进 `PAID` 并发奖。
4. 任一异常被本分支捕获并只记 WARN，不向 Controller 抛出。
5. Controller 因此返回成功，PayPal 不会因本次业务失败自动重试；订单保持原状态。

#### 其他事件

只记 DEBUG，不改变任何订单。

**幂等与缺口**

- 同一订单的并发由订单行锁串行。
- 金币由订单幂等键和唯一索引防重。
- 没有记录 PayPal event id，重复事件无法直接去重或审计。
- 没有通知状态更新；`notify_status/notify_count/notify_last_at` 虽在表中，但当前处理代码不写。
- Webhook 处理同步依赖 PayPal 验签接口和本地数据库，无法快速接收后异步处理。

### 3.5 查询订单

**调用链**

```text
PaymentController.getOrder
  → PaymentServiceImpl.getOrder
  → PaymentOrderManager.findByOrderId
  → payment_orders
  → PaymentOrderVO
```

**执行过程**

1. Controller 只接收内部订单号。
2. Service 查询订单，不存在时抛业务异常。
3. 将订单号、用户、商品、金额、币种、通道、状态和创建时间组装为摘要。
4. 不访问 PayPal，不刷新订单状态。
5. 不接收当前用户身份，也不校验订单归属，这是越权读取风险。

## 4. 金币生命周期

### 4.1 查询金币余额

**入口**：REST `/balance`，gRPC `GetBalance` 和 `GetCoins`。

**调用链**

```text
CoinController / PaymentGrpcService
  → CoinServiceImpl.getCoins
  → CoinAccountManager.getOrCreate
  → CoinAccountMapper.insertIfAbsent
  → CoinAccountMapper.selectById
  → CoinAccountVO
```

**执行过程**

1. REST 入口是 POST；gRPC 的两个查询方法最终共用 `getBalance` 实现。
2. `insertIfAbsent` 用 `ON CONFLICT DO NOTHING` 保证首次并发查询不会因重复建账户失败。
3. 随后按用户主键读取账户。
4. 组装免费余额、付费余额及二者之和。
5. 无 Redis 缓存；数据库是实时来源。

**一致性**：查询不是只读，因为不存在账户时会插入零余额行。

### 4.2 增加免费币与付费币

两条流程结构相同，区别是更新字段和返回余额类型。

**调用链**

```text
CoinController.addCoins/addPaidCoins
  或 PaymentGrpcService.addCoins/addPaidCoins
  → CoinServiceImpl.addCoins/addPaidCoins [事务]
  → validateMutation
  → CoinLedgerManager.findByIdempotencyKey
  → CoinAccountManager.getOrCreateForUpdate
      → INSERT ... ON CONFLICT DO NOTHING
      → SELECT ... FOR UPDATE
  → 再查幂等流水
  → CoinLedgerMapper.insert
  → CoinAccountMapper.updateBalances
```

**执行过程**

1. 校验用户编号为正、数量大于 0、幂等键非空。
2. 事务外层先按“用户 + 幂等键”查流水；命中则返回历史变更后余额。
3. 未命中时确保账户存在，并对用户账户行加 `FOR UPDATE` 锁。
4. 锁内再次查幂等键，处理等待锁期间已经完成的同一动作。
5. 免费发币计算 `newBalance = balance + amount`；付费发币计算 `newPaidBalance = paidBalance + amount`。
6. 插入 `INCOME` 流水，免费和付费变动分别记录在不同字段。
7. 更新双余额，同时把 `version` 自增 1。
8. 事务提交后，流水和余额一起可见。

**并发与失败**

- 当前并发控制是数据库悲观锁。
- 更新 SQL 不带旧版本条件，`version` 只是每次余额更新递增的账户修订计数；服务已删除无效的乐观锁重试。
- 唯一索引冲突不会在 Manager 中捕获并反查；冲突会使事务失败。
- 金币余额使用 BIGINT，但接口数量是 int；服务没有加法溢出检查。

### 4.3 消费金币

**调用链**

```text
CoinController.consumeCoins / PaymentGrpcService.consumeCoins
  → CoinServiceImpl.consumeCoins [事务]
  → precheck idempotency
  → getOrCreateForUpdate
  → locked idempotency recheck
  → balance check and split
  → insert EXPENSE ledger
  → update both balances
  → ConsumeResult
```

**执行过程**

1. 金额不大于 0时直接返回业务失败 `4001`，不抛异常。
2. 用户编号无效或幂等键为空时同样返回业务失败，不访问账户。
3. 先查历史流水。命中后把历史免费余额和付费余额相加，作为第一次处理后的总余额返回。
4. 未命中则创建并锁定用户账户行。
5. 锁内再次检查相同幂等键。
6. 计算总余额；不足时返回 `3001`，不写流水、不更新账户。
7. 余额足够时按以下规则拆分：

   ```text
   freeTake = min(amount, freeBalance)
   paidTake = amount - freeTake
   ```

8. 插入 `EXPENSE` 流水，免费和付费变动量都使用负数。
9. 更新账户双余额并递增版本字段。
10. 返回新的总余额。

**调用方衔接**

- match-service 的 SuperHi 先保存本地操作与配额预留，再以 `operationKey` 扣币；余额不足会回滚配额并删除未完成操作，RPC 异常则保留进度供同键恢复。
- im-service 客户端把消息号编码成 `im-msg:<messageId>`，但当前源码没有发现该客户端的实际业务调用。

**关键风险**

幂等命中只按键判断，不比较操作类型、金额或描述。调用方若用相同键提交不同金额，服务仍会把第一次结果当作成功重放。

### 4.4 查询金币流水

#### REST 实现

**调用链**

```text
CoinController.getLedger
  → CoinLedgerManager.findByUserIdPaged
  → CoinLedgerMapper.findByUserIdOrderByCreatedAtDesc
  → CoinLedgerManager.countByUserId
  → CoinController.toVO
```

**执行过程**

1. 默认第 1 页、每页 20 条。
2. 计算 `offset = (page - 1) * pageSize`。
3. SQL 按 `created_at DESC` 查询一页。
4. 另执行一次 `COUNT(*)` 获取总数。
5. 把变动量、双余额、原因、扩展数据和时间转换成返回对象。

没有页码和页大小上限，也没有按 `id` 的稳定次序补充。

#### gRPC 占位

`PaymentGrpcService.getCoinLedger` 只规范化页码，然后构造 `total=0` 的空响应，没有调用 Manager。它是“协议存在、实现缺失”，不能作为流水查询能力使用。

## 5. 订阅生命周期

### 5.1 查询订阅

**入口**：REST `SubscriptionController.getSubscription`、gRPC `GetSubscription`。

**调用链**

```text
Controller / PaymentGrpcService
  → SubscriptionServiceImpl.getSubscription
  → SubscriptionCacheManager.getFromCache
  ├─ hit and valid → return
  └─ miss/expired/failure
      → UserSubscriptionManager.findActiveByUserId
      → UserSubscriptionMapper.findActiveByUserId
      → buildSubscriptionVO
      → SubscriptionCacheManager.putToCache
```

**执行过程**

1. 用 `putao:payment:subscription:<userId>` 读取 Redis。
2. Redis 返回对象后，若是 active 订阅，比较毫秒级到期时间；过期则删除并回源。
3. Redis 异常只记 WARN，视为 miss。
4. 数据库只查 `deleted=false` 的一条记录。
5. 无记录、到期时间为空、已到期或档位不高于 FREE 时，构造 FREE/inactive/0。
6. 其他记录构造实际档位、active 和毫秒到期时间。
7. active 缓存 TTL 取 24 小时与剩余有效期的较小值；FREE 缓存 30 分钟。
8. gRPC 把整数档位映射回 proto 枚举。

**降级**：Redis 故障回源 DB；match-service 的 DB/RPC 整体失败则把档位降为 FREE。

### 5.2 激活或续期订阅

**入口**：支付发奖内部调用，或 gRPC `ActivateSubscription`。

**调用链**

```text
PaymentGrpcService.activateSubscription
  或 PaymentServiceImpl.grantReward
  → SubscriptionServiceImpl.activateSubscription [事务]
  → UserSubscriptionManager.findActiveByUserId
  ├─ insert new
  └─ update existing
  → register afterCommit cache eviction
```

**执行过程**

1. tier 不高于 FREE 时不写数据库，立即删除缓存并返回 0。
2. 读取当前未删除订阅；查询没有 `FOR UPDATE`。
3. 无记录时，以 `now + durationDays` 创建记录。
4. 有记录且未过期时，只在新档位更高时升级档位，并从原到期时间顺延天数。
5. 有记录但已过期时，切换成新档位，从当前时间重新计算。
6. 更新来源字段。
7. 如果事务同步已启用，注册 `afterCommit` 删除缓存；否则立即删除。
8. 返回数据库实体的到期时间。

**事务与风险**

- 支付发奖调用时，它加入支付外层事务，缓存只在整笔支付本地事务提交后失效。
- 支付来源传 `"PayPal"`，与 V6 CHECK 冲突。
- gRPC 来源可映射 PAYPAL/STRIPE，但迁移也不允许。
- 同一用户并发续费可能丢失一次时长。
- gRPC 响应的 tier 直接回显请求档位，不一定是“只升不降”后的实际档位。
- durationDays、userId、source 和 tier 上限缺少业务校验。

## 6. 钱包与提现

### 6.1 查询钱包余额

**调用链**

```text
WithdrawController.getBalance
  → WithdrawServiceImpl.getBalance
  → UserWalletManager.findByUserId
  → UserWalletMapper.selectById
```

**执行过程**

1. 按用户主键读取 `user_wallets`。
2. 有记录时把 `BigDecimal balance` 转成 long，丢弃小数部分。
3. 无记录时返回 0。
4. 不创建钱包、不返回冻结余额，也不查询钱包流水。

### 6.2 绑定、申请和历史

三个 Controller 已接好参数，但 Service 分别直接抛 `NOT_IMPLEMENTED`：

```text
bindAccount → throw
apply       → throw
getHistory → throw
```

因此：

- 不写 `user_wallets`；
- 不写 `user_wallet_entries`；
- 不写或查 `withdraw_records`；
- 不冻结余额；
- 不触发外部打款；
- 不存在提现状态推进、审核或补偿。

相关 Manager/Mapper/Entity 只是预留基础设施。

## 7. 遗留和运维入口

### 7.1 `PurchaseCoins`

该 gRPC 方法不调用任何 Service，固定返回空订单号、0 金币和时间 0。响应看起来像正常完成，但没有购买行为，应视为危险占位而非兼容实现。

### 7.2 健康检查

`HealthController.health` 固定返回字符串 `OK`。它不检查 PostgreSQL、Redis、Nacos、gRPC 或 PayPal。Spring Actuator 另暴露 health/info/metrics/prometheus，但自定义 `/health` 只能证明 Web 进程能响应。

### 7.3 错误处理

REST：

- `PaymentBizException` 返回 HTTP 200 + 业务 code。
- `IllegalArgumentException` 返回 HTTP 400。
- 其他异常返回 HTTP 500。

gRPC：

- `PaymentGrpcService.sendError` 当前会真正 `onError`，旧文档的“空函数”已过时。
- 但所有异常统一变成 `Status.INTERNAL`，传入的业务 code 未使用。

## 8. 存储清单

| 存储 | 当前读写者 | 当前用途 |
|---|---|---|
| `payment_orders` | PaymentService/Manager | 本地订单、收款/发奖状态 |
| `coin_accounts` | CoinService/Manager | 免费币、付费币、版本计数 |
| `coin_ledger` | CoinService/Controller | 收支流水与幂等记录 |
| `user_subscription` | SubscriptionService | 当前未删除订阅 |
| `user_wallets` | WithdrawService | 仅余额读取 |
| `user_wallet_entries` | 无业务 Service | 预留 |
| `withdraw_records` | 无业务 Service | 预留 |
| Redis subscription key | SubscriptionCacheManager | 订阅查询缓存 |
| 其他 payment Redis key 常量 | 无 | 仅预留，不能描述成已用 |

## 9. 外部依赖与异步流

```text
App/Web ─REST─┐
match-service ─gRPC─┼→ payment-service → PostgreSQL
im-service client ─gRPC─┘            └→ Redis（仅订阅缓存）
                                        └→ PayPal REST

PayPal ─Webhook REST→ 验签 → 同步订单处理与发奖
```

没有 MQ、任务表、定时补偿或 outbox。所谓“最终一致”目前主要依赖客户端/PayPal 重试、订单状态和本地幂等，并没有完整自动恢复闭环。

## 10. 实现状态与风险总表

| 能力 | 状态 | 关键事实 |
|---|---|---|
| 商品列表 | 已实现 | 内存硬编码 |
| PayPal 创建订单 | 已实现意图 | 外部先成功、本地后落单 |
| 主动确认 | 部分实现 | 行锁与强校验已加入；当前源码不能编译 |
| Webhook 验签 | 已实现 | 远程验签，缺配置即拒绝 |
| Webhook 去重 | 未实现 | 无 event id 表 |
| 普通金币包发奖 | 已实现意图 | 订单锁 + 金币幂等 |
| 订阅包发奖 | 阻断 | source 值违反迁移 CHECK |
| 金币增加/消费 | 已实现 | 悲观行锁，不是乐观锁 |
| 金币唯一键冲突反查 | 未实现 | Manager 直接 insert |
| REST 流水 | 已实现 | offset 分页，无上限 |
| gRPC 流水 | 占位 | 固定空页 |
| 订阅查询缓存 | 已实现 | Cache-Aside + 到期感知 |
| 并发订阅续期 | 部分实现 | 可能丢失更新 |
| 提现 | 占位 | 只有钱包余额读取 |
| 退款/对账/补偿 | 未实现 | 无 API、任务或消息 |
| 内部调用鉴权 | 未实现 | 客户端传 token，服务端不验 |
| 数据迁移 | 阻断/高风险 | 重复 V1，且允许 clean-on-validation-error |

## 11. 功能到代码索引

| 功能 | 核心代码 |
|---|---|
| 商品 | `ProductInfoServiceImpl.init/getAllProducts/getProduct` |
| 下单 | `PaymentController.createOrder`、`PaymentServiceImpl.createOrder/createPayPalOrder` |
| PayPal 适配 | `PaypalExecutor.getAccessToken/createOrder/captureOrder/verifyWebhookSignature` |
| 主动确认 | `PaymentServiceImpl.verifyPayment/validateCapture/grantReward` |
| Webhook | `WebhookController.handlePayPalWebhook`、`PaymentServiceImpl.handlePayPalWebhook/validateWebhookOrder` |
| 订单锁与状态 | `PaymentOrderMapper.findByOrderIdForUpdate`、`PaymentOrderManager.updateStatus` |
| 金币查询 | `CoinServiceImpl.getCoins`、`CoinAccountMapper.insertIfAbsent` |
| 金币变更 | `CoinServiceImpl.addCoins/addPaidCoins/consumeCoins` |
| 金币并发 | `CoinAccountMapper.selectByUserIdForUpdate/updateBalances` |
| 金币幂等 | `CoinLedgerMapper.findByUserIdAndIdempotencyKey`、V6 唯一索引 |
| REST 流水 | `CoinController.getLedger`、`CoinLedgerMapper.findByUserIdOrderByCreatedAtDesc` |
| gRPC 流水占位 | `PaymentGrpcService.getCoinLedger` |
| 订阅查询 | `SubscriptionServiceImpl.getSubscription/buildSubscriptionVO` |
| 订阅缓存 | `SubscriptionCacheManager`、`PaymentRedisKey.subscription` |
| 订阅续期 | `SubscriptionServiceImpl.activateSubscription` |
| 提现占位 | `WithdrawServiceImpl` |
| gRPC 委托 | `PaymentGrpcServer`、`PaymentGrpcService` |
| 协议 | `proto/payment/payment.proto` |
| 数据约束 | `src/main/resources/db/migration/V1~V6` |
