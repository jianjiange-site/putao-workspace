# payment-service 特殊场景设计

> 本文只讨论具有架构价值的场景。产品功能与完整调用过程分别见《payment-service-业务清单》和《payment-service-业务流程详解》。

## 1. 主动确认与 Webhook 并发到达

### 1.1 业务背景与数据特征

PayPal 支付完成后，客户端可能立即主动确认，PayPal 也可能同时发送 `PAYMENT.CAPTURE.COMPLETED` 或 `CHECKOUT.ORDER.APPROVED`。两条链路处理的是同一笔钱和同一份权益，但到达顺序、重试次数和网络结果均不可控。

核心数据有三个身份：

- 内部订单号：本地业务主键和金币发奖幂等键来源。
- PayPal order id：外部 capture 与查询使用。
- PayPal webhook transmission/event 信息：证明回调来源和识别重复事件。

当前代码保存前两个身份，但没有持久化事件 ID。

### 1.2 核心冲突

- 即时体验要求客户端能主动确认，最终到达要求保留异步通知。
- 两条路径都可能执行 capture 和发奖，必须防止重复。
- 外部 PayPal 调用不能被本地数据库事务回滚。
- 已收款但本地失败时，系统必须能识别并恢复。

### 1.3 方案比较

| 方案 | 优点 | 缺点 |
|---|---|---|
| 只靠前端避免重复 | 实现简单 | 网络重试、用户连击和 Webhook 都会绕过 |
| 只判断订单状态 | 直观 | 无锁的先查后写存在竞争窗口 |
| Redis 分布式锁 | 跨实例互斥、速度快 | 锁丢失或过期不能承担账务最终正确性 |
| 数据库行锁 + 状态条件更新 | 与权威订单同库，易审计 | 锁内外部 HTTP 会拉长事务 |
| 支付事件表 + 权益发放表 + 异步执行 | 可去重、可恢复、可独立重试 | 模型和运维复杂度更高 |

### 1.4 当前实现

主动确认和 Webhook 最终都进入 `PaymentServiceImpl`，并通过：

```java
orderManager.findByOrderIdForUpdate(orderId)
```

执行：

```sql
SELECT *
FROM payment_orders
WHERE order_id = ?
FOR UPDATE
```

同一订单在数据库层串行处理。状态更新同时带期望状态：

```java
updateStatus(orderId, PAID, INIT, FAILED);
updateStatus(orderId, GRANTED, PAID);
```

金币发奖还使用：

```text
order:<orderId>
```

作为 `coin_ledger` 的业务幂等键，并由 `(user_id, idempotency_key)` 部分唯一索引兜底。

Webhook 入口当前会：

1. 将原始 payload 和 PayPal 签名头提交给 PayPal 验签接口。
2. 缺少 `webhook-id`、凭据或必要签名头时拒绝回调。
3. 用 `custom_id` 或 PayPal order id 定位本地订单。
4. 校验内部订单号、外部订单号、金额和币种。
5. capture 响应额外校验可选商户号。

这已经替代旧文档中“验签参数完全未使用”的过时结论。

### 1.5 一致性与失败边界

当前 `verifyPayment` 和 `handlePayPalWebhook` 都是本地事务方法。它们在持有订单行锁期间调用 PayPal，再在同一事务中写订单、订阅、金币流水和金币账户。

好处是：本地发奖任一步失败都会回滚本地状态。

代价和风险：

- 行锁覆盖外部 HTTP，PayPal 慢响应会占用事务和连接。
- capture 已在 PayPal 成功，本地事务仍可能回滚；外部结果不会随之回滚。
- 主动 capture 失败时，代码先把订单改为 `FAILED` 再抛 `PaymentBizException`，异常触发事务回滚，所以 `FAILED` 通常不会提交。
- `CHECKOUT.ORDER.APPROVED` 分支捕获并吞掉 capture/发奖异常，Controller 最终仍返回成功；订单保持原状态。
- 没有事件 ID 唯一表，重复 Webhook 只能依赖订单锁、状态和金币幂等，无法审计“哪个事件处理过”。
- 没有扫描 `PAID` 的任务、消息或人工补偿入口。旧文档中的“定时补偿已存在”不是项目事实。

### 1.6 当前限制与演进

推荐的演进顺序：

1. 先修复当前源码编译和订阅来源约束，使现有本地事务链路可运行。
2. 增加 `payment_events(event_id unique, payload_hash, status)`，先落事件再处理。
3. 增加 `order_entitlements(order_id, entitlement_type unique)`，让金币与订阅各自可判重。
4. 把外部 capture 移到短事务之外；本地只提交已核验的支付事实。
5. 用 outbox/任务表驱动权益发放，允许至少一次执行，并以权益唯一键达到业务一次性。
6. 增加 `PAID` 超时扫描、失败次数、下次重试时间和人工对账状态。

这些是演进方案，不是当前实现。

## 2. 金币扣减：行锁、幂等键与双余额

### 2.1 业务背景与数据特征

金币消费具有典型账务特征：

- 同一用户的操作集中到一行账户。
- match-service 或 im-service 可能因超时重试。
- 免费币和付费币来源不同，但消费时需要组合判断余额。
- 余额快照与流水必须一起成功或一起失败。

### 2.2 核心冲突

- 并发扣减不能超扣或丢失更新。
- 重复请求不能重复扣币。
- 余额不足不能产生伪消费流水。
- 幂等重放需要返回第一次请求的结果。

### 2.3 方案比较

| 方案 | 并发特点 | 适用性 |
|---|---|---|
| 应用内锁 | 只保护单实例 | 多实例不可用 |
| Redis 锁 | 可跨实例 | 锁与数据库事务不是一个原子边界 |
| 乐观锁 `WHERE version=?` | 低冲突吞吐好 | 冲突时必须重新读取、重算并正确重试 |
| 悲观行锁 `FOR UPDATE` | 同一用户严格串行 | 热点用户会排队，但实现和正确性更直观 |
| 单条条件扣减 SQL | 锁持有时间短 | 双余额优先级和流水回写更复杂 |

### 2.4 当前实现

当前实现实际上选择了悲观行锁，而不是旧文档描述的乐观锁：

```sql
INSERT INTO coin_accounts(user_id, balance, paid_balance, version)
VALUES (?, 0, 0, 0)
ON CONFLICT (user_id) DO NOTHING;

SELECT *
FROM coin_accounts
WHERE user_id = ?
FOR UPDATE;
```

锁内执行第二次幂等检查，然后计算余额、插入流水并更新账户：

```sql
UPDATE coin_accounts
SET balance = ?,
    paid_balance = ?,
    version = version + 1,
    updated_at = NOW()
WHERE user_id = ?;
```

这里虽然递增 `version`，但 `WHERE` 没有旧版本条件；它只是账户修订计数。并发安全来自 `FOR UPDATE`，实体已不再使用 `@Version`，Service 中无效的乐观锁重试也已删除。

双余额扣减公式为：

```text
freeTake = min(requestAmount, freeBalance)
paidTake = requestAmount - freeTake
newFree = freeBalance - freeTake
newPaid = paidBalance - paidTake
```

流水与账户更新处于同一 Spring 事务。数据库还有非负 CHECK 约束作为最后防线。

### 2.5 幂等与异常边界

当前使用两次查询：

1. 加锁前快速查询 `(user_id, idempotency_key)`。
2. 取得用户账户行锁后再次查询。

这能让所有经过当前金币服务、且属于同一用户的重复动作在锁内串行。数据库唯一索引提供最终拒绝重复插入的约束。

但 `CoinLedgerManager.saveWithIdempotencyCheck` 当前只是直接 `insert`，已经不再捕获 `DuplicateKeyException` 并反查历史记录。因此：

- 正常的同用户并发重试通常会在锁内第二次查询命中并返回。
- 如果仍撞上唯一索引，当前请求会异常并回滚，而不是像旧文档所说“捕获冲突并返回已有结果”。
- 唯一范围不含操作类型。同一用户若把一个键先用于发币、后用于扣币，后续动作会误把前一流水当成自己的幂等结果。
- 幂等键长度没有应用层校验，超过数据库 64 字符会失败。

### 2.6 当前限制与演进

- 当前已经统一为悲观行锁模型；后续应通过锁等待时间和事务耗时验证这一选择，而不是重新混入无效的版本重试。
- 幂等记录应保存业务类型、请求摘要和结果摘要；重复键但请求内容不同应报冲突。
- 为幂等键建立命名规范，例如 `order:...`、`superhi:...`、`im-msg:...`。
- 高热点场景可考虑账户分片、单用户串行队列或条件更新 SQL，但必须继续保证流水与余额同事务。

## 3. 订阅 Cache-Aside 与自然到期

### 3.1 业务背景与数据特征

订阅查询远多于激活，match-service 的 feed、滑动、配额和 SuperHi 都需要档位。订阅又会在没有写操作的情况下因时间流逝自然到期，因此普通固定 TTL 缓存可能返回已经过期的高级权益。

### 3.2 核心冲突

- 高频读需要缓存。
- 订阅到期是时间事件，不一定伴随数据库更新。
- 支付事务提交前删除缓存，可能让并发查询把旧 DB 数据重新写回缓存。
- Redis 故障不能让核心查询完全不可用。

### 3.3 当前实现

唯一实际使用的 Redis key 是：

```text
putao:payment:subscription:<userId>
```

`PaymentRedisKey` 中的订单、金币账户、订单锁和通用幂等 key 只是预留常量，当前没有读写代码。

读取过程：

1. 先读 Redis。
2. 命中且为激活订阅时，再比较毫秒级 `expiresAt` 与当前时间。
3. 已过期则删除缓存并回源。
4. 未命中则查数据库并转换成对外订阅状态。
5. 有效订阅 TTL 为 `min(24 小时, 距到期时间)`，至少 1 秒；FREE 结果缓存 30 分钟。
6. Redis 异常只记录告警，继续访问数据库。

写入过程：

- 付费档位更新数据库后，通过事务同步回调在 `afterCommit` 删除缓存。
- FREE 或更低档位直接跳过数据库写入，并立即删除缓存。

旧注释提到“写前删除 + 提交后再删除”，但付费档位实际只有提交后删除，不能写成双删已实现。

### 3.4 一致性与并发边界

- 提交后删除避免了数据库回滚却提前清掉正确缓存。
- 数据库提交到缓存删除之间仍存在极短的旧值窗口。
- 有效订阅 TTL 不越过到期点，配合读时检查，限制了自然过期误判。
- FREE 负缓存降低空记录查询，但管理员在数据库外直接改订阅时最多可能陈旧 30 分钟。
- `user_subscription` 没有版本字段或行锁。两个并发续期都读取相同到期时间时，后写可能覆盖前写，丢失一次购买时长。
- 部分唯一索引只保证一个未删除记录，不解决同一行的丢失更新。

### 3.5 演进

- 使用 `SELECT ... FOR UPDATE` 或 `UPDATE ... SET expires_at = GREATEST(expires_at, now()) + duration` 原子顺延。
- 将实际生效档位和到期时间作为更新结果返回，避免 gRPC 回显请求档位。
- 若订阅取消/退款加入系统，应统一走同一 Service 并在提交后失效缓存。
- 大规模场景可用订阅变更事件通知其他服务失效本地缓存，但 payment-service 的数据库仍应是权威源。

## 4. 跨服务订阅降级与金币扣费边界

### 4.1 业务背景

match-service 同时消费两类能力：

- 读订阅档位，决定 feed、右划和 SuperHi 配额。
- 写金币账本，为超出免费额度的 SuperHi 扣币。

读失败和写失败的业务风险不同，不能使用同一种降级。

### 4.2 当前策略

订阅读取失败时，match-service 返回 FREE：

```text
payment-service 不可用
  → getSubscriptionTier 捕获异常
  → DEFAULT_TIER = FREE
  → 按免费配额执行
```

这是“不给未确认的高级权益”的保守降级。代价是已付费用户在故障期间体验降级。

金币扣费失败时，match-service 不假装成功：

- payment-service 明确返回余额不足，SuperHi 回滚配额预留并删除未完成操作。
- gRPC 异常则向上抛出，match-service 记录操作进度，允许使用同一操作键恢复。
- payment-service 只保证自己的余额与流水，不参与 match-service 数据库事务。

这形成了跨服务 Saga 风格边界：调用方保存业务操作状态，payment-service 通过稳定幂等键让扣币可重试。

### 4.3 当前风险

- 调用端 blocking stub 没有逐请求 deadline，网络故障可能长时间占用线程。
- gRPC 连接为 plaintext。
- 调用端附带内部令牌，但 payment-service 没有服务端验证拦截器，元数据目前不构成安全边界。
- payment-service 将所有异常映射成 `INTERNAL`，调用方难以区分参数错误、订单不存在和暂时故障。
- im-service 定义的读写超时常量没有用于 stub；其支付客户端当前也没有找到业务调用点。

### 4.4 演进

- 为读写 RPC 分别设置 deadline、重试和熔断策略；扣币重试必须复用同一业务键。
- 增加服务端身份认证、授权矩阵和传输加密。
- 将业务失败保留为明确响应或稳定的 gRPC status/details，不要全部折叠成 `INTERNAL`。
- 对订阅降级建立指标，区分真实 FREE 与依赖故障导致的临时 FREE。

## 5. 数据库迁移与启动安全

### 5.1 为什么这是特殊场景

支付服务的数据结构决定账务约束。迁移失败会阻止服务启动；更危险的是，错误的清理策略可能删除已有账务数据。因此迁移不是普通部署细节，而是支付可靠性的一部分。

### 5.2 当前事实

迁移目录同时存在：

```text
V1__init_complete_payment.sql
V1__init_wallet_and_payment.sql
```

两者都位于 `classpath:db/migration`。虽然合并版注释声称“不参与 Flyway 启动”，但文件名和目录没有让 Flyway 忽略它，形成重复 V1 版本。

配置同时包含：

```yaml
spring:
  flyway:
    clean-disabled: false
    clean-on-validation-error: true
```

对于账务库，这是高风险配置：迁移校验异常不应通过自动 clean 处理。

订阅来源也存在代码与迁移不一致：

- Java/Proto 支持 PAYPAL、STRIPE。
- V6 CHECK 只允许 IAP_APPLE、IAP_GOOGLE、TEST、ADMIN。
- 支付发奖实际传入大小写为 `PayPal`，与两边都不一致。

此外，当前 `application.yml` 给 Nacos 地址、用户名和密码提供了仓库内默认值，并非旧文档所说的“全部只用环境变量占位”。

### 5.3 风险控制与演进

1. 合并版迁移移出 Flyway 扫描目录，或改成不符合迁移命名规则的学习材料。
2. 已发布迁移只追加修复版本，不修改历史校验和。
3. 生产关闭自动 clean，并将迁移验证放入 CI/CD。
4. 用新迁移扩展订阅来源约束，并统一代码传值为常量 `PAYPAL`。
5. 将所有凭据默认值移出仓库，通过环境或密钥管理系统注入。
6. 增加真实 PostgreSQL 的迁移与约束集成测试，覆盖普通金币包、订阅包、重复回调和并发续期。
