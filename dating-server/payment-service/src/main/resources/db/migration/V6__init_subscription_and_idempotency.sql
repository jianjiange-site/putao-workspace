-- V6: 订阅模块 + 金币幂等键
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

-- 部分唯一索引：生效中只一条 (deleted=false)
CREATE UNIQUE INDEX idx_subscription_user_active ON user_subscription(user_id) WHERE deleted = FALSE;

-- 给 coin_ledger 添加幂等键字段 + 部分唯一索引
ALTER TABLE coin_ledger ADD COLUMN idempotency_key VARCHAR(64);

-- 部分唯一索引：(user_id, idempotency_key) WHERE key IS NOT NULL
CREATE UNIQUE INDEX idx_ledger_idempotency ON coin_ledger(user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
