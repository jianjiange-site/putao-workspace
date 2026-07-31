-- =============================================================
-- Match Service Database Schema V3
-- Production reliability: worker leases, durable delayed match,
-- and recoverable Super Hi operations.
-- =============================================================

ALTER TABLE match_outbox
    ADD COLUMN event_key VARCHAR(160),
    ADD COLUMN locked_by VARCHAR(100),
    ADD COLUMN locked_until TIMESTAMPTZ;

UPDATE match_outbox
   SET event_key = 'legacy:' || id
 WHERE event_key IS NULL;

CREATE UNIQUE INDEX uk_match_outbox_event_key
    ON match_outbox (event_key)
    WHERE NOT deleted;

CREATE INDEX idx_outbox_claim
    ON match_outbox (status, next_retry_at, locked_until)
    WHERE NOT deleted;

CREATE TABLE delayed_match_task (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    dh_user_id      BIGINT NOT NULL,
    source          VARCHAR(30) NOT NULL,
    execute_at      TIMESTAMPTZ NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ NOT NULL,
    status          VARCHAR(20) NOT NULL,
    locked_by       VARCHAR(100),
    locked_until    TIMESTAMPTZ,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id, dh_user_id, source)
);

CREATE INDEX idx_delayed_match_claim
    ON delayed_match_task (status, next_retry_at, locked_until)
    WHERE NOT deleted;

CREATE TABLE super_hi_operation (
    id                  BIGINT PRIMARY KEY,
    operation_key       VARCHAR(160) NOT NULL,
    user_id             BIGINT NOT NULL,
    target_user_id      BIGINT NOT NULL,
    target_user_type    SMALLINT NOT NULL,
    subscription_tier   SMALLINT NOT NULL,
    coins_used          INT NOT NULL DEFAULT 0,
    status              VARCHAR(30) NOT NULL,
    match_id            BIGINT,
    last_error          VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted             BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (operation_key)
);

CREATE INDEX idx_super_hi_recovery
    ON super_hi_operation (status, updated_at)
    WHERE NOT deleted;
