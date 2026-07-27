-- V1 (合并版): payment-service 完整数据库结构
-- 原始 6 个迁移脚本的等价物，仅用于本地学习/复习。
-- 生产环境请保留 V1~V6 的拆分记录，本文件不参与 Flyway 启动。
--
-- 演进历史：
--   V1 钱包模块 + 支付订单
--   V2 提现记录表
--   V3 金币模块基础版 (免费币)
--   V4 金币模块增强版 - 付费币分离
--   V5 订单 GRANTED 状态 + 回调通知增强
--   V6 订阅模块 + 金币幂等键
--
-- =============================================================
-- 1. 钱包模块 (法币钱包，提现用，当前业务未启用)
-- =============================================================

-- user_wallets: 法币钱包，每用户一行，乐观锁
CREATE TABLE user_wallets (
    user_id         BIGINT PRIMARY KEY,
    balance         NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    frozen_balance  NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (frozen_balance >= 0),
    version         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- user_wallet_entries: 钱包流水 (append-only 审计)
CREATE TABLE user_wallet_entries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    entry_type      VARCHAR(20) NOT NULL CHECK (entry_type IN ('INCOME', 'WITHDRAW_FREEZE', 'WITHDRAW_SUCCESS', 'WITHDRAW_FAIL', 'ADMIN_ADJUST')),
    amount          NUMERIC(16,4) NOT NULL,
    before_balance  NUMERIC(16,4) NOT NULL,
    after_balance   NUMERIC(16,4) NOT NULL,
    order_id        VARCHAR(64),                   -- V2 从 order_no 改名对齐 proto
    withdraw_no     VARCHAR(64),
    reason          VARCHAR(255),
    extra           JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wallet_entries_user_id     ON user_wallet_entries(user_id);
CREATE INDEX idx_wallet_entries_created_at  ON user_wallet_entries(created_at DESC);

-- =============================================================
-- 2. 支付订单 (V1 + V5 合并)
-- =============================================================

-- payment_orders: 支付订单
CREATE TABLE payment_orders (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    order_id            VARCHAR(64) NOT NULL,       -- 业务订单号，唯一索引
    product_id          VARCHAR(128) NOT NULL,      -- 内部商品 id / Apple ProductID
    amount              NUMERIC(16,4) NOT NULL,     -- 金额（元）
    currency            VARCHAR(10) NOT NULL DEFAULT 'USD',
    payment_channel     VARCHAR(32) NOT NULL CHECK (payment_channel IN ('APPLE_IAP', 'GOOGLE_BILLING', 'PAYPAL', 'STRIPE')),
    status              VARCHAR(20) NOT NULL DEFAULT 'INIT' CHECK (status IN ('INIT', 'PAID', 'GRANTED', 'FAILED')),
    refund_status       VARCHAR(20) NOT NULL DEFAULT 'NONE' CHECK (refund_status IN ('NONE', 'PARTIAL', 'FULL')),
    refunded_amount     NUMERIC(16,4) NOT NULL DEFAULT 0,
    ext_transaction_id  VARCHAR(128),               -- 第三方交易号 (PayPal order id)
    notify_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notify_count        INT NOT NULL DEFAULT 0,
    notify_last_at      TIMESTAMPTZ,
    return_url          VARCHAR(512),               -- V5: PayPal 跳转返回 URL
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- V5: notify_status 状态枚举扩展到 NOTIFIED/CONFIRMED/FAILED
    CONSTRAINT payment_orders_notify_status_check
        CHECK (notify_status IN ('PENDING', 'NOTIFIED', 'CONFIRMED', 'FAILED'))
);

CREATE UNIQUE INDEX idx_orders_order_id     ON payment_orders(order_id);
CREATE INDEX        idx_orders_user_id      ON payment_orders(user_id);
CREATE INDEX        idx_orders_status       ON payment_orders(status);
CREATE INDEX        idx_orders_created_at   ON payment_orders(created_at DESC);

-- =============================================================
-- 3. 提现记录
-- =============================================================

-- withdraw_records: 提现记录
CREATE TABLE withdraw_records (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    withdraw_no     VARCHAR(64) NOT NULL,           -- 唯一订单号
    amount          NUMERIC(16,4) NOT NULL,         -- 含手续费
    fee             NUMERIC(16,4) NOT NULL DEFAULT 0,
    real_amount     NUMERIC(16,4) NOT NULL,         -- 到账 = amount - fee
    payment_channel VARCHAR(32) NOT NULL CHECK (payment_channel IN ('PAYPAL', 'STRIPE', 'BANK')),
    channel_account VARCHAR(255) NOT NULL,          -- 收款账户
    status          VARCHAR(20) NOT NULL DEFAULT 'INIT' CHECK (status IN ('INIT', 'AUDITING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REJECTED')),
    fail_reason     VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_withdraw_no     ON withdraw_records(withdraw_no);
CREATE INDEX        idx_withdraw_user_id ON withdraw_records(user_id);
CREATE INDEX        idx_withdraw_status  ON withdraw_records(status);

-- =============================================================
-- 4. 金币账户 (V3 + V4 合并：免费币 + 付费币)
-- =============================================================

-- coin_accounts: 金币账户，每用户一行，乐观锁
CREATE TABLE coin_accounts (
    user_id         BIGINT PRIMARY KEY,
    balance         BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),        -- 免费币余额
    paid_balance    BIGINT NOT NULL DEFAULT 0 CHECK (paid_balance >= 0),  -- V4: 付费币余额
    version         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================
-- 5. 金币流水 (V3 + V4 + V6 合并：免费/付费 + 幂等键)
-- =============================================================

-- coin_ledger: 金币流水 (append-only)
CREATE TABLE coin_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    amount              BIGINT NOT NULL,                 -- 免费币变动量
    balance_after       BIGINT NOT NULL,                 -- 变动后免费余额
    paid_amount         BIGINT NOT NULL DEFAULT 0,       -- V4: 付费币变动量
    paid_balance_after  BIGINT NOT NULL DEFAULT 0,       -- V4: 变动后付费余额
    reason              VARCHAR(255),
    extra               JSONB,
    idempotency_key     VARCHAR(64),                     -- V6: 幂等键
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coin_ledger_user_id     ON coin_ledger(user_id);
CREATE INDEX idx_coin_ledger_created_at  ON coin_ledger(created_at DESC);

-- V6: 部分唯一索引，避免同一幂等键并发双写
CREATE UNIQUE INDEX idx_ledger_idempotency
    ON coin_ledger(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- =============================================================
-- 6. 订阅模块 (V6)
-- =============================================================

-- user_subscription: 用户订阅表
CREATE TABLE user_subscription (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    tier        SMALLINT NOT NULL CHECK (tier IN (1, 2, 3, 4)),  -- 1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY
    expires_at  TIMESTAMPTZ,                                       -- NULL 或 < now() 视为过期
    source      VARCHAR(20) NOT NULL CHECK (source IN ('IAP_APPLE', 'IAP_GOOGLE', 'TEST', 'ADMIN')),
    deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 部分唯一索引：同一用户最多一条生效中的订阅
CREATE UNIQUE INDEX idx_subscription_user_active
    ON user_subscription(user_id)
    WHERE deleted = FALSE;
