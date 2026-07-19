-- =============================================================
-- Match Service Database Schema V2
-- Description: like_record + visit_record + dh_interaction_task
-- =============================================================

-- 1. like_record — Like 记录(谁单向喜欢了我,未配对)
--    一旦触发 match,5.3 createMatch 同事务 DELETE 双向 like_record
CREATE TABLE like_record (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    from_user_type  SMALLINT NOT NULL,               -- 1=BH 2=DH
    source          SMALLINT NOT NULL,               -- 1=SWIPE_RIGHT 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    like_content    VARCHAR(200),                    -- DH 任务携带的文案;真人 swipe 为 NULL
    liked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX idx_like_to_user_time ON like_record (to_user_id, liked_at DESC) WHERE deleted = false;
CREATE INDEX idx_like_to_user_type ON like_record (to_user_id, from_user_type, liked_at DESC) WHERE deleted = false;

COMMENT ON TABLE like_record IS 'Like 记录 — 只存单向未回应喜欢;UPADTE 更新 source/liked_at';
COMMENT ON COLUMN like_record.source IS '1=SWIPE_RIGHT 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE';

-- 2. visit_record — Visit 记录(谁访问了谁)
CREATE TABLE visit_record (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    from_user_type  SMALLINT NOT NULL,
    source          SMALLINT NOT NULL,               -- 1=PROFILE_VIEW 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    visit_count     INT NOT NULL DEFAULT 1,
    visited_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX idx_visit_to_user_time ON visit_record (to_user_id, visited_at DESC) WHERE deleted = false;
CREATE INDEX idx_visit_to_user_type ON visit_record (to_user_id, from_user_type, visited_at DESC) WHERE deleted = false;

COMMENT ON TABLE visit_record IS 'Visit 记录 — UNIQUE(from,to) UPSERT 累加 visit_count';

-- 3. dh_interaction_task — DH 模拟互动任务(短生命周期)
--    generator 写、executor 读+删;执行后硬删
CREATE TABLE dh_interaction_task (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,                 -- DH user_id(发起方)
    to_user_id      BIGINT NOT NULL,                 -- 真人 user_id(接收方)
    action          SMALLINT NOT NULL,               -- 1=LIKE 2=VISIT
    scene           SMALLINT NOT NULL,               -- 1=ONLINE 2=OFFLINE
    execute_time    TIMESTAMPTZ NOT NULL,
    like_content    VARCHAR(200),                    -- action=LIKE 才填
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dh_task_execute_time ON dh_interaction_task (execute_time);
CREATE INDEX idx_dh_task_to_user_scene ON dh_interaction_task (to_user_id, scene);

COMMENT ON TABLE dh_interaction_task IS 'DH 模拟互动任务 — 执行后硬删,不软删不审计';
COMMENT ON COLUMN dh_interaction_task.action IS '1=LIKE 2=VISIT';
COMMENT ON COLUMN dh_interaction_task.scene IS '1=ONLINE 2=OFFLINE';
