-- V1: 钱包模块 (提现用，当前未启用业务)
-- user_wallets: 法币钱包，每用户一行，乐观锁
CREATE TABLE user_wallets (
    user_id    BIGINT PRIMARY KEY,
    balance    NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    frozen_balance NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (frozen_balance >= 0),
    version    INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
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

CREATE INDEX idx_wallet_entries_user_id ON user_wallet_entries(user_id);
CREATE INDEX idx_wallet_entries_created_at ON user_wallet_entries(created_at DESC);

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
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_orders_order_id ON payment_orders(order_id);
CREATE INDEX idx_orders_user_id ON payment_orders(user_id);
CREATE INDEX idx_orders_status ON payment_orders(status);
CREATE INDEX idx_orders_created_at ON payment_orders(created_at DESC);
