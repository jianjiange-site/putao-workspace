-- =============================================================
-- IM Service Database Schema
-- Version: V1__init_im_tables.sql
-- Description: Initialize IM tables for dating app
-- =============================================================

-- 1. conversation — 会话表
CREATE TABLE conversation (
    id              BIGSERIAL PRIMARY KEY,
    conv_id         BIGINT UNIQUE NOT NULL,
    user_id         BIGINT NOT NULL,
    peer_id         BIGINT NOT NULL,
    last_msg_id     BIGINT,
    last_msg_at     TIMESTAMPTZ,
    unread_count    INT DEFAULT 0,
    status          SMALLINT DEFAULT 1,            -- 1=正常 / 0=已删除
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_conversation_user ON conversation(user_id, updated_at DESC) WHERE deleted = 0;
CREATE UNIQUE INDEX idx_conversation_pair ON conversation(user_id, peer_id) WHERE deleted = 0;

COMMENT ON TABLE conversation IS '会话表';
COMMENT ON COLUMN conversation.peer_id IS '对方用户ID';

-- 2. message — 消息表
CREATE TABLE message (
    id              BIGSERIAL PRIMARY KEY,
    msg_id          BIGINT UNIQUE NOT NULL,
    conv_id         BIGINT NOT NULL,
    sender_id       BIGINT NOT NULL,
    msg_type        SMALLINT NOT NULL,             -- 1=文本 / 2=图片 / 3=语音 / 4=视频 / 5=礼物
    content         TEXT,
    metadata        JSONB,
    status          SMALLINT DEFAULT 1,            -- 1=发送中 / 2=已发送 / 3=已读 / 4=撤回
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_message_conv ON message(conv_id, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_message_sender ON message(sender_id, created_at DESC);

COMMENT ON TABLE message IS '消息表';
COMMENT ON COLUMN message.msg_type IS '1=文本 / 2=图片 / 3=语音 / 4=视频 / 5=礼物';

-- 3. message_attachment — 消息附件
CREATE TABLE message_attachment (
    id              BIGSERIAL PRIMARY KEY,
    msg_id          BIGINT NOT NULL REFERENCES message(msg_id) ON DELETE CASCADE,
    file_key        VARCHAR(128) NOT NULL,        -- MinIO object key
    file_name       VARCHAR(128),
    file_size       BIGINT,
    mime_type       VARCHAR(64),
    width           INT,
    height          INT,
    duration        INT,                           -- 音视频时长,秒
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_attachment_msg ON message_attachment(msg_id);

COMMENT ON TABLE message_attachment IS '消息附件表';
COMMENT ON COLUMN message_attachment.file_key IS 'MinIO object key';

-- 4. conversation_read — 已读状态
CREATE TABLE conversation_read (
    id              BIGSERIAL PRIMARY KEY,
    conv_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    last_read_msg_id BIGINT NOT NULL,
    last_read_at    TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(conv_id, user_id)
);

CREATE INDEX idx_conv_read_conv ON conversation_read(conv_id);

COMMENT ON TABLE conversation_read IS '会话已读状态表';

-- 5. im_token — IM 实时通信 Token
CREATE TABLE im_token (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT UNIQUE NOT NULL,
    token           VARCHAR(256) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    platform        SMALLINT,                     -- 1=iOS / 2=Android / 3=Web
    device_id       VARCHAR(128),
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE im_token IS 'IM 实时通信 Token 表';
