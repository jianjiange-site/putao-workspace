# payment-service 知识点整理

> 本文按“场景 → 问题 → 方案 → 实现 → 权衡”组织。结论以当前代码和迁移脚本为准，并单独标出生产化补强项。

## 知识点一：支付订单为什么拆成 PAID 和 GRANTED

### 场景

用户完成 PayPal 支付后，系统需要给付费币、激活订阅。第三方支付成功与本地权益发放不是同一个动作。

### 问题

如果只有 `SUCCESS` 一个状态，无法区分：

- 钱已经收到，但本地发奖失败；
- 回调重复到达；
- 客户端主动确认和 Webhook 同时处理；
- 订单已经发奖，是否还能安全重试。

### 方案

使用 `INIT → PAID → GRANTED`：

- `INIT`：内部订单和第三方订单已建立，等待付款。
- `PAID`：PayPal capture 已确认收款。
- `GRANTED`：金币/订阅权益已成功发放。
- `FAILED`：支付或 capture 失败。

`grantReward` 以 `GRANTED` 作为幂等锚点，重复进入时直接跳过。

### 代码证据

```117:153:dating-server/payment-service/src/main/java/com/dating/payment/service/impl/PaymentServiceImpl.java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerifyPaymentVO verifyPayment(Long userId, String orderId, String extOrderId) {
        // 1. 查询订单
        PaymentOrderEntity order = orderManager.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND));

        // 2. 已支付/已发奖直接返回
        if (OrderStatus.PAID.equals(order.getStatus()) || OrderStatus.GRANTED.equals(order.getStatus())) {
            VerifyPaymentVO vo = new VerifyPaymentVO();
            vo.setOrderId(orderId);
            vo.setStatus(order.getStatus());
            return vo;
        }
```

```220:262:dating-server/payment-service/src/main/java/com/dating/payment/service/impl/PaymentServiceImpl.java
    /**
     * 发放奖励（幂等：GRANTED 状态直接跳过）.
     */
    private void grantReward(String orderId, String source) {
        PaymentOrderEntity order = orderManager.findByOrderId(orderId).orElse(null);
        if (order == null) {
            log.warn("grantReward order not found: {}", orderId);
            return;
        }

        // 幂等锚点
        if (OrderStatus.GRANTED.equals(order.getStatus())) {
            log.info("grantReward already granted: orderId={}", orderId);
            return;
        }

        ProductVO product = productInfoService.getProduct(order.getProductId());
        if (product == null) {
            log.error("grantReward product not found: productId={}", order.getProductId());
            return;
        }

        Long userId = order.getUserId();

        // 1. 订阅商品：先激活/续期订阅
        if (productInfoService.isSubscriptionProduct(product.getProductId())) {
            int tier = product.getSubscriptionTier();
            int days = product.getSubscriptionDays();
            subscriptionService.activateSubscription(userId, tier, days, source);
        }

        // 2. 发付费金币
        coinService.addPaidCoins(userId, product.getCoins(),
                "Purchase: " + product.getName(),
                "order:" + orderId);

        // 3. 标记 GRANTED
        order.setStatus(OrderStatus.GRANTED);
        orderManager.updateById(order);
```

### 权衡与生产化补强

拆状态让补偿任务可以扫描 `PAID` 订单继续发奖，但当前实现还要补充：

1. `grantReward` 不能只依赖先读后写的状态判断。应使用条件更新或发奖记录唯一键，防止两个线程同时看到非 `GRANTED` 并各自发奖。
2. `addPaidCoins` 的幂等键 `order:<orderId>` 可以防止金币重复增加，但订阅激活也需要自己的订单权益幂等记录。
3. 应增加 `PAID` 超时、发奖失败重试和人工对账任务。

## 知识点二：金币幂等的两层防线

### 场景

match-service 消费金币时可能因网络超时重试；支付 Webhook 也可能重复通知。一次业务请求必须最多产生一次账务变更。

### 方案

第一层是快速查询，第二层是数据库唯一索引：

1. 业务开始先按 `(user_id, idempotency_key)` 查询已有流水。
2. 没命中时插入流水。
3. 并发请求即使同时通过查询，也会被部分唯一索引拦住。
4. 捕获 `DuplicateKeyException` 后读取已存在流水，返回历史结果。

### 代码证据

```43:81:dating-server/payment-service/src/main/java/com/dating/payment/service/impl/CoinServiceImpl.java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addCoins(Long userId, int amount, String reason, String key) {
        // 1. 幂等检查
        if (key != null && !key.isBlank()) {
            Optional<CoinLedgerEntity> existing = coinLedgerManager.findByIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                log.info("AddCoins idempotent hit: userId={}, key={}", userId, key);
                return existing.get().getBalanceAfter();
            }
        }
```

```78:87:dating-server/payment-service/src/main/java/com/dating/payment/manager/CoinLedgerManager.java
    public CoinLedgerEntity saveWithIdempotencyCheck(CoinLedgerEntity entity, Long userId, String key) {
        try {
            coinLedgerMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            log.warn("Duplicate idempotency key: userId={}, key={}", userId, key);
            return coinLedgerMapper.findByUserIdAndIdempotencyKey(userId, key)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key conflict but record not found"));
        }
    }
```

```17:21:dating-server/payment-service/src/main/resources/db/migration/V6__init_subscription_and_idempotency.sql
-- 给 coin_ledger 添加幂等键字段 + 部分唯一索引
ALTER TABLE coin_ledger ADD COLUMN idempotency_key VARCHAR(64);

-- 部分唯一索引：(user_id, idempotency_key) WHERE key IS NOT NULL
CREATE UNIQUE INDEX idx_ledger_idempotency ON coin_ledger(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
```

### 关键理解

幂等键不是“请求随机 ID”，而应该表达一次业务动作。例如 `order:<orderId>`、`superhi:<userId>:<targetId>:<day>`。同一个动作重试必须复用同一个 key；不同动作不能误用同一个 key。

### 权衡

- 只用 Redis：速度快，但 key 过期、丢失或主从切换会造成重复账务。
- 只用应用层查询：并发窗口内会双写。
- 数据库唯一索引：把最终正确性放在账务权威存储，代价是重复请求会发生一次异常分支。

## 知识点三：乐观锁处理金币并发

### 场景

两个请求同时给用户扣币或加币，不能出现丢失更新，例如余额 100 被两个请求分别读到后都写成 90。

### 方案

账户表保存 `version`，实体使用 MyBatis-Plus `@Version`；更新失败视为版本冲突，服务最多重试 3 次。

```23:35:dating-server/payment-service/src/main/java/com/dating/payment/entity/CoinAccountEntity.java
    /** 免费金币余额，CHECK >= 0 */
    private Long balance;

    /** 付费金币余额（V4 新增），CHECK >= 0 */
    private Long paidBalance;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
```

```142:183:dating-server/payment-service/src/main/java/com/dating/payment/service/impl/CoinServiceImpl.java
        // 2. 循环重试乐观锁更新
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                CoinAccountEntity account = coinAccountManager.getOrCreate(userId);
                long totalBalance = account.getBalance() + account.getPaidBalance();

                // 3. 余额不足
                if (totalBalance < amount) {
                    log.warn("ConsumeCoins insufficient: userId={}, balance={}, required={}",
                            userId, totalBalance, amount);
                    return ConsumeResult.insufficient();
                }

                // 4. 计算扣减顺序：先扣免费，再扣付费
                long freeTake = Math.min(amount, account.getBalance());
                long paidTake = amount - freeTake;
```

### 为什么不用悲观锁

金币消费通常是单用户单行更新，冲突概率可控。乐观锁不需要长时间持有数据库锁，吞吐更好；重试次数有限，避免高竞争用户无限自旋。

### 当前实现的审查点

学习时要特别注意：`CoinAccountManager.updateBalance` 通过 `updateById` 更新实体，但构造的新实体没有显式设置读取到的 `version`。应确认 MyBatis-Plus 生成的 SQL 是否真正带版本条件；更稳妥的实现是显式传入旧版本并检查受影响行数，或使用 `WHERE user_id=? AND version=?` 的单条 SQL，同时原子增加 version。否则“代码声明了 `@Version`”不等于并发控制一定生效。

此外，`getOrCreate` 是“先查再插”，两个并发请求首次访问同一用户可能同时插入。应依赖主键冲突重查，或使用数据库 `INSERT ... ON CONFLICT DO NOTHING`。

## 知识点四：免费币和付费币为什么分账户

### 场景

免费赠送币和用户充值币在运营、退款、消耗优先级及审计上可能不同。

### 设计

- `balance` 记录免费币。
- `paid_balance` 记录充值/购买产生的付费币。
- 总余额是两者之和。
- 消费按“先免费、后付费”扣除。
- 流水同时记录 `amount`、`paid_amount` 及两个变更后余额。

### 公式

```text
freeTake = min(requestAmount, freeBalance)
paidTake = requestAmount - freeTake
newFree = freeBalance - freeTake
newPaid = paidBalance - paidTake
```

这样既保留了免费币优先消耗的运营策略，又不会把两类资产混成无法解释的单一数字。

### 权衡

双账户增加了更新字段和流水字段，但换来了充值币审计、退款处理、活动币过期等未来扩展空间。生产系统还应明确币种的过期规则、退款回滚顺序和负数调整权限。

## 知识点五：订阅续期的时间语义

### 规则

当前订阅未过期时，续费从 `currentExpires` 顺延，而不是从 now 重新计算；已过期则从 now 开始。档位取新旧最大值，不允许购买低档位把当前高档位降级。

```70:106:dating-server/payment-service/src/main/java/com/dating/payment/service/impl/SubscriptionServiceImpl.java
        Instant now = Instant.now();
        Optional<UserSubscriptionEntity> optEntity = subscriptionManager.findActiveByUserId(userId);

        UserSubscriptionEntity entity;
        if (optEntity.isEmpty()) {
            // 无记录 → INSERT
            entity = new UserSubscriptionEntity();
            entity.setUserId(userId);
            entity.setTier(tier);
            entity.setExpiresAt(now.plus(durationDays, ChronoUnit.DAYS));
            entity.setSource(source);
            entity.setDeleted(false);
            subscriptionManager.save(entity);
        } else {
            entity = optEntity.get();
            Instant currentExpires = entity.getExpiresAt();

            if (currentExpires != null && currentExpires.isAfter(now)) {
                // 未过期 → 档位只升不降，时长顺延
                if (tier > entity.getTier()) {
                    entity.setTier(tier);
                }
                entity.setExpiresAt(currentExpires.plus(durationDays, ChronoUnit.DAYS));
            } else {
                // 已过期 → 档位=新档位，从 now 重新算
                entity.setTier(tier);
                entity.setExpiresAt(now.plus(durationDays, ChronoUnit.DAYS));
            }
```

### 并发注意点

数据库部分唯一索引只保证同时存在一条 active 记录，不自动保证两个并发续费不会发生“后写覆盖前写”的时长丢失。生产实现应加版本号、行锁或基于 SQL 的原子延长。

## 知识点六：第三方支付适配器与 Token 缓存

`PaypalExecutor` 把 OAuth token、创建订单、capture 等外部协议细节隔离在 executor 中，业务服务只表达“创建订单”和“capture”。Token 在进程内缓存，并在过期前提前刷新，减少每次 API 调用都鉴权的开销。

### 适配器的收益

- 业务层不依赖 PayPal JSON 字段和 URL。
- 未来可实现 `AppleIapExecutor`、`StripeExecutor`，不污染订单编排。
- 第三方异常可统一映射为 `PaymentBizException`。

### 当前实现的生产化问题

1. 项目规则要求服务间调用使用 gRPC，但 PayPal 是外部支付平台，使用 HTTPS REST 是合理的例外；必须保留在 payment-service 内部，不向业务服务暴露 HTTP 依赖。
2. Webhook Controller 接收了验签相关 Header，但当前没有调用 PayPal 验签接口，`sig` 等参数未使用。未验签的回调不能直接改变账务状态。
3. `payload` 以 INFO 级别完整打印，可能造成敏感数据泄漏，应只记录事件 ID、类型和内部订单号，并脱敏。
4. 手工拼接 JSON 和直接插入 return URL 应改为对象序列化，避免特殊字符破坏请求体。
5. Token 缓存是单实例内存缓存，多实例会各自刷新；通常可接受，但要设置连接超时、读取超时、重试和熔断。

## 知识点七：事务边界与跨服务调用

支付服务本地的订单、金币、订阅写入应在本地事务内完成，不能做跨服务分布式事务。match-service 调用 `ConsumeCoins` 时，payment-service 只负责自己的账户和流水；调用方若后续业务失败，需要自己的补偿语义。

当前 `grantReward` 在 payment-service 内调用金币和订阅 Service，属于同库本地编排，适合放在事务边界内。PayPal 网络调用发生在事务方法中，会拉长数据库事务；更理想的生产链路是：外部确认先完成，再用本地状态机和可靠补偿发奖，避免长事务包住第三方网络请求。

## 知识点八：gRPC 契约与兼容性

`payment.proto` 通过枚举和独立请求/响应消息表达领域契约，其他服务只依赖 proto，不依赖 payment-service Java 内部类。`GetBalance` 和 `GetCoins` 目前都返回双余额，兼容旧接口。

### 当前代码审查点

- `getCoinLedger` 仍是 TODO，gRPC 返回空列表，不能把“接口存在”误认为“功能完成”。
- `PurchaseCoins` 是遗留占位 RPC，返回空订单和 0 金币，调用方迁移前不能使用。
- `PaymentGrpcService.sendError` 当前没有向 observer 发送真正的错误响应，异常可能导致客户端既收不到业务错误也收不到完成信号。应统一构造每个 RPC 的错误响应，或使用 gRPC `StatusRuntimeException`。
- `consumeCoins` 的“兼容旧字段名”分支目前读取的是同一个字段，说明字段迁移需要测试覆盖和 proto 版本治理。

## 知识点九：配置、时区和数据安全

服务 application 配置负责端口、应用名、Nacos 导入、gRPC 端口、MyBatis-Plus 和 UTC；数据库 URL、账号、Redis 密码、PayPal 凭据等敏感项应从 Nacos/环境变量注入。

- PostgreSQL 使用 `TIMESTAMPTZ`。
- Hikari connection init 设置 UTC。
- Java 时间使用 `Instant`。
- 对外返回 epoch milliseconds，展示层再转换本地时区。
- Redis key 前缀为 `putao:payment`；当前服务主要状态在 PostgreSQL，不能把 Redis 当账务数据库。

## 学习总结

payment-service 的核心不是“调用支付 SDK”，而是把外部不可靠事件转化成内部可审计、可重试、可幂等的价值变更：

```text
外部支付事件
  → 内部订单状态机
  → 本地事务确认
  → 幂等权益发放
  → 双余额账户 + append-only 流水
  → gRPC 提供可复用的价值能力
```
