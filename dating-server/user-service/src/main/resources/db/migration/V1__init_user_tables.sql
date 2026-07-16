-- =============================================================
-- User Service Database Schema
-- Version: V1__init_user_tables.sql
-- Description: Initialize user tables for dating app
-- =============================================================

-- 1. user_info — 用户基本信息
CREATE TABLE user_info (
    user_id         BIGINT PRIMARY KEY,
    phone           VARCHAR(20) UNIQUE,
    nickname        VARCHAR(64) NOT NULL,
    gender          SMALLINT DEFAULT 0,         -- 0=未知 / 1=男 / 2=女
    birthday        DATE,
    city_code       VARCHAR(20),
    bio             VARCHAR(256),
    status          SMALLINT DEFAULT 1,          -- 0=禁用 / 1=正常 / 2=审核中
    vip_until       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_user_info_phone ON user_info(phone) WHERE phone IS NOT NULL;
CREATE INDEX idx_user_info_gender_status ON user_info(gender, status) WHERE deleted = 0;
CREATE INDEX idx_user_info_created_at ON user_info(created_at DESC);

COMMENT ON TABLE user_info IS '用户基本信息';
COMMENT ON COLUMN user_info.user_id IS '雪花ID,业务主键';
COMMENT ON COLUMN user_info.gender IS '0=未知 / 1=男 / 2=女';

-- 2. user_profile — 用户详细资料
CREATE TABLE user_profile (
    user_id         BIGINT PRIMARY KEY REFERENCES user_info(user_id) ON DELETE CASCADE,
    height          SMALLINT,
    weight          SMALLINT,
    education       VARCHAR(32),
    occupation      VARCHAR(64),
    income          VARCHAR(32),
    interest_tags   TEXT[],                      -- PostgreSQL 数组类型
    dating_intent   VARCHAR(64),                 -- 交友目的
    looking_for     VARCHAR(64),                 -- 期望对象
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE user_profile IS '用户详细资料';
COMMENT ON COLUMN user_profile.interest_tags IS '兴趣标签数组';

-- 3. user_avatar — 用户头像
CREATE TABLE user_avatar (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    avatar_key      VARCHAR(128) NOT NULL,
    sort_order      SMALLINT DEFAULT 0,
    is_verified     SMALLINT DEFAULT 0,          -- 是否已审核
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_avatar_user ON user_avatar(user_id, sort_order);

COMMENT ON TABLE user_avatar IS '用户头像表';
COMMENT ON COLUMN user_avatar.avatar_key IS 'MinIO object key';

-- 4. user_login_phone — 手机登录
CREATE TABLE user_login_phone (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    phone           VARCHAR(20) NOT NULL,
    password_hash   VARCHAR(128),
    country_code    VARCHAR(8) DEFAULT '86',
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE UNIQUE INDEX idx_user_login_phone_phone ON user_login_phone(phone) WHERE deleted = 0;

COMMENT ON TABLE user_login_phone IS '手机登录表';
COMMENT ON COLUMN user_login_phone.password_hash IS 'BCrypt 加密后的密码';

-- 5. user_third_party — 第三方登录
CREATE TABLE user_third_party (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    platform        VARCHAR(32) NOT NULL,         -- apple / google / facebook
    open_id         VARCHAR(128) NOT NULL,
    union_id        VARCHAR(128),
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE UNIQUE INDEX idx_user_third_party_platform_openid ON user_third_party(platform, open_id) WHERE deleted = 0;

COMMENT ON TABLE user_third_party IS '第三方登录表';
COMMENT ON COLUMN user_third_party.platform IS 'apple / google / facebook';

-- 6. user_device — 设备注册
CREATE TABLE user_device (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    device_id       VARCHAR(128) NOT NULL,
    platform        SMALLINT NOT NULL,            -- 1=iOS / 2=Android / 3=Web
    device_model    VARCHAR(64),
    os_version      VARCHAR(32),
    app_version     VARCHAR(32),
    push_token      VARCHAR(512),
    last_active_at  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT DEFAULT 0
);

CREATE INDEX idx_user_device_user ON user_device(user_id) WHERE deleted = 0;
CREATE UNIQUE INDEX idx_user_device_device ON user_device(device_id) WHERE deleted = 0;

COMMENT ON TABLE user_device IS '用户设备表';

-- 7. user_interest — 用户兴趣标签
CREATE TABLE user_interest (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    tag             VARCHAR(32) NOT NULL,
    weight          SMALLINT DEFAULT 1,            -- 标签权重
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, tag)
);

CREATE INDEX idx_user_interest_user ON user_interest(user_id);
CREATE INDEX idx_user_interest_tag ON user_interest(tag);

COMMENT ON TABLE user_interest IS '用户兴趣标签表';
