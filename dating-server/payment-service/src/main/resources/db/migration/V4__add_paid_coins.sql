-- V4: 金币模块增强版 - 付费币分离
-- 给 coin_accounts 添加付费币余额列
ALTER TABLE coin_accounts ADD COLUMN paid_balance BIGINT NOT NULL DEFAULT 0 CHECK (paid_balance >= 0);

-- 给 coin_ledger 添加付费币相关字段
ALTER TABLE coin_ledger ADD COLUMN paid_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE coin_ledger ADD COLUMN paid_balance_after BIGINT NOT NULL DEFAULT 0;
