-- =============================================================
-- ShedLock 表初始化
-- 时区统一 UTC,使用 TIMESTAMPTZ
-- =============================================================

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)   NOT NULL,
    lock_until TIMESTAMPTZ   NOT NULL,
    locked_at  TIMESTAMPTZ   NOT NULL,
    locked_by  VARCHAR(255)  NOT NULL,
    PRIMARY KEY (name)
);
