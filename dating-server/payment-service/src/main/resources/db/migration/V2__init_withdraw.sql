-- V2: 提现记录表 + 部分索引修复
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

CREATE UNIQUE INDEX idx_withdraw_no ON withdraw_records(withdraw_no);
CREATE INDEX idx_withdraw_user_id ON withdraw_records(user_id);
CREATE INDEX idx_withdraw_status ON withdraw_records(status);
