-- =============================================================
-- Match Service Database Schema V1
-- Description: 划卡历史 + match 主表 + match outbox 发件箱
-- =============================================================

-- 1. user_swipe_history — 划卡历史(高写入,按 user_id 索引,30 天归档)
CREATE TABLE user_swipe_history (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    target_user_id      BIGINT NOT NULL,
    target_user_type    SMALLINT NOT NULL,         -- 1=BH, 2=DH
    direction           SMALLINT NOT NULL,         -- 1=LEFT, 2=RIGHT, 3=SUPER_HI
    swiped_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted             BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id, target_user_id)
);
CREATE INDEX idx_swipe_user_time ON user_swipe_history (user_id, swiped_at DESC);
CREATE INDEX idx_swipe_target_dir ON user_swipe_history (target_user_id, direction) WHERE deleted = false;

COMMENT ON TABLE user_swipe_history IS '划卡历史表 — 权威已 swipe 记录,Redis SET 是缓存';
COMMENT ON COLUMN user_swipe_history.direction IS '1=LEFT, 2=RIGHT, 3=SUPER_HI';

-- 2. match — 匹配关系(主键保证 (a,b) 与 (b,a) 视为同一)
CREATE TABLE match (
    id              BIGINT PRIMARY KEY,
    user_id_low     BIGINT NOT NULL,                 -- min(uid1, uid2)
    user_id_high    BIGINT NOT NULL,                 -- max(uid1, uid2)
    matched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    source          VARCHAR(30) NOT NULL,            -- 入口动作维度,SWIPE_MATCH / SWIPE_SUPER_HI / ...
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id_low, user_id_high)
);
CREATE INDEX idx_match_low_time  ON match (user_id_low,  matched_at DESC);
CREATE INDEX idx_match_high_time ON match (user_id_high, matched_at DESC);

COMMENT ON TABLE match IS '匹配关系表 — (user_id_low, user_id_high) UNIQUE 保证幂等';

-- 3. match_outbox — 副作用 retry(IM 建会话 / 系统消息 / DH 开场白)
CREATE TABLE match_outbox (
    id            BIGINT PRIMARY KEY,
    match_id      BIGINT NOT NULL,
    action        VARCHAR(40) NOT NULL,              -- ENSURE_CONVERSATION | SYSTEM_MSG | DH_OPENING
    payload_json  JSONB NOT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL,              -- PENDING | DONE | DEAD
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted       BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_outbox_pending ON match_outbox (next_retry_at) WHERE status = 'PENDING' AND NOT deleted;
CREATE INDEX idx_outbox_match   ON match_outbox (match_id);

COMMENT ON TABLE match_outbox IS 'match 副作用发件箱 — 失败 retry';
