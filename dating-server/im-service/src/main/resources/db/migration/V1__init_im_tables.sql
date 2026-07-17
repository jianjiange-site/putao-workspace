-- =============================================================
-- IM Service Database Schema
-- Version: V1__init_im_tables.sql
-- Description: Initialize IM tables for dating app
-- =============================================================

-- 1. chat_messages — 消息流水表
CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    message_id      VARCHAR(128) UNIQUE NOT NULL,
    conv_id         BIGINT,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    content         TEXT,
    type            SMALLINT NOT NULL DEFAULT 1,     -- 1=文本 2=图片 3=语音 4=视频 5=礼物
    route_type      VARCHAR(16) NOT NULL,             -- BH_BH / BH_DH / DH_BH / DH_DH
    metadata        JSONB,                            -- 图片URL等元数据
    conversation_type VARCHAR(16) NOT NULL DEFAULT 'SINGLE',
    provider        VARCHAR(32) DEFAULT 'openim',     -- IM 引擎来源
    timestamp       BIGINT,                          -- 发送时间戳(epoch 秒)
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_chat_messages_conv ON chat_messages(conv_id, timestamp DESC) WHERE deleted = 0;
CREATE INDEX idx_chat_messages_from ON chat_messages(from_user_id, timestamp DESC);
CREATE INDEX idx_chat_messages_to ON chat_messages(to_user_id, timestamp DESC);
CREATE INDEX idx_chat_messages_route ON chat_messages(route_type, timestamp DESC) WHERE deleted = 0;

COMMENT ON TABLE chat_messages IS '消息流水表';
COMMENT ON COLUMN chat_messages.route_type IS 'BH_BH=真人到真人 / BH_DH=真人到数字人 / DH_BH=数字人到真人 / DH_DH=数字人到数字人';

-- 2. user_online_session — 在线会话表
CREATE TABLE user_online_session (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    platform            SMALLINT,                     -- 1=iOS / 2=Android / 3=Web
    online_at           TIMESTAMPTZ NOT NULL,
    offline_at          TIMESTAMPTZ,                   -- NULL = 还在线
    duration_seconds    INT,                          -- 在线时长(秒)
    created_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT DEFAULT 0
);

CREATE INDEX idx_user_online_session_user ON user_online_session(user_id, online_at DESC);
CREATE INDEX idx_user_online_session_offline ON user_online_session(offline_at)
    WHERE offline_at IS NOT NULL AND deleted = 0;

COMMENT ON TABLE user_online_session IS '用户在线会话表,记录上线/下线历史,数字人不记录';
COMMENT ON COLUMN user_online_session.offline_at IS 'NULL表示还在线,按此建部分索引';
