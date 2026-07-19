-- V3: 金币模块基础版 (免费币)
-- coin_accounts: 金币账户，每用户一行，乐观锁
CREATE TABLE coin_accounts (
    user_id     BIGINT PRIMARY KEY,
    balance     BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),  -- 免费金币余额
    version     INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- coin_ledger: 金币流水 (append-only)
CREATE TABLE coin_ledger (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    type            VARCHAR(20) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    amount          BIGINT NOT NULL,                 -- 免费币变动量
    balance_after   BIGINT NOT NULL,                 -- 变动后免费余额
    reason          VARCHAR(255),
    extra           JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coin_ledger_user_id ON coin_ledger(user_id);
CREATE INDEX idx_coin_ledger_created_at ON coin_ledger(created_at DESC);
