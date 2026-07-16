-- =============================================================
-- Match Service Database Schema
-- Version: V1__init_match_tables.sql
-- Description: Initialize match tables for dating app
-- =============================================================

-- 1. match — 匹配记录
CREATE TABLE match_record (
    id              BIGSERIAL PRIMARY KEY,
    match_id        BIGINT UNIQUE NOT NULL,
    user_id         BIGINT NOT NULL,
    target_user_id  BIGINT NOT NULL,
    match_type      SMALLINT NOT NULL,             -- 1=普通喜欢 / 2=超级喜欢
    status          SMALLINT DEFAULT 1,            -- 0=已取消 / 1=待确认 / 2=已匹配 / 3=已过期
    matched_at      TIMESTAMPTZ,
    expired_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_match_record_user ON match_record(user_id, created_at DESC);
CREATE INDEX idx_match_record_target ON match_record(target_user_id) WHERE deleted = 0;
CREATE INDEX idx_match_record_status ON match_record(status, created_at DESC) WHERE deleted = 0;

COMMENT ON TABLE match_record IS '匹配记录表';
COMMENT ON COLUMN match_record.match_type IS '1=普通喜欢 / 2=超级喜欢';
COMMENT ON COLUMN match_record.status IS '0=已取消 / 1=待确认 / 2=已匹配 / 3=已过期';

-- 2. match_outbox — 写扩散发件箱(发消息用)
CREATE TABLE match_outbox (
    id              BIGSERIAL PRIMARY KEY,
    match_id        BIGINT NOT NULL,
    event_type      VARCHAR(32) NOT NULL,         -- MATCH_CREATED / MATCH_CONFIRMED / MATCH_EXPIRED
    payload         JSONB NOT NULL,
    status          SMALLINT DEFAULT 0,            -- 0=待处理 / 1=已处理 / 2=失败
    retry_count     SMALLINT DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_match_outbox_match ON match_outbox(match_id);
CREATE INDEX idx_match_outbox_status ON match_outbox(status, next_retry_at) WHERE status = 0;

COMMENT ON TABLE match_outbox IS '匹配事件发件箱(写扩散)';

-- 3. user_swipe — 滑动历史
CREATE TABLE user_swipe (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    target_user_id  BIGINT NOT NULL,
    action          SMALLINT NOT NULL,            -- 1=喜欢 / 2=超级喜欢 / 3=不喜欢 / 4=反悔
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, target_user_id)
);

CREATE INDEX idx_user_swipe_user ON user_swipe(user_id, created_at DESC);
CREATE INDEX idx_user_swipe_target ON user_swipe(target_user_id);

COMMENT ON TABLE user_swipe IS '用户滑动历史表';
COMMENT ON COLUMN user_swipe.action IS '1=喜欢 / 2=超级喜欢 / 3=不喜欢 / 4=反悔';

-- 4. like_record — 喜欢记录(简化版)
CREATE TABLE like_record (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    target_user_id  BIGINT NOT NULL,
    like_type       SMALLINT NOT NULL,            -- 1=普通 / 2=超级
    status          SMALLINT DEFAULT 1,            -- 1=有效 / 0=已撤销
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, target_user_id)
);

CREATE INDEX idx_like_record_user ON like_record(user_id, created_at DESC);
CREATE INDEX idx_like_record_target ON like_record(target_user_id, created_at DESC) WHERE status = 1;

COMMENT ON TABLE like_record IS '喜欢记录表';

-- 5. visit_record — 访问记录(谁看过我)
CREATE TABLE visit_record (
    id              BIGSERIAL PRIMARY KEY,
    visitor_id      BIGINT NOT NULL,
    visited_id      BIGINT NOT NULL,
    visit_type      SMALLINT DEFAULT 1,            -- 1=普通访问 / 2=深度查看
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(visitor_id, visited_id)
);

CREATE INDEX idx_visit_record_visitor ON visit_record(visitor_id, created_at DESC);
CREATE INDEX idx_visit_record_visited ON visit_record(visited_id, created_at DESC);

COMMENT ON TABLE visit_record IS '访问记录表';

-- 6. dh_task — Daily Hero 任务
CREATE TABLE dh_task (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    task_date       DATE NOT NULL,
    target_user_id  BIGINT,
    status          SMALLINT DEFAULT 0,            -- 0=待完成 / 1=已完成
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, task_date)
);

CREATE INDEX idx_dh_task_user ON dh_task(user_id, task_date DESC);
CREATE INDEX idx_dh_task_status ON dh_task(status, task_date) WHERE status = 0;

COMMENT ON TABLE dh_task IS 'Daily Hero 每日任务表';

-- 7. coins — 金币系统
CREATE TABLE coins (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT UNIQUE NOT NULL,
    balance         INT DEFAULT 0,
    total_earned    INT DEFAULT 0,
    total_spent     INT DEFAULT 0,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

COMMENT ON TABLE coins IS '用户金币余额表';

-- 8. coin_transaction — 金币流水
CREATE TABLE coin_transaction (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    amount          INT NOT NULL,                 -- 正=收入, 负=支出
    biz_type        VARCHAR(32) NOT NULL,         -- SWIPE_SUPER_LIKE / VIP_PURCHASE / DAILY_BONUS
    biz_id          VARCHAR(64),
    balance_before  INT NOT NULL,
    balance_after   INT NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coin_txn_user ON coin_transaction(user_id, created_at DESC);
CREATE INDEX idx_coin_txn_biz ON coin_transaction(biz_type, created_at DESC);

COMMENT ON TABLE coin_transaction IS '金币流水表';
