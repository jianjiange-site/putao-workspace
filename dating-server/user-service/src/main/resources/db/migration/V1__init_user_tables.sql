-- ============================================================================
-- User Service Database Schema
-- Version: V1__init_user_tables.sql
-- Description: 对齐 doc/specs/user-service-design.md §1 数据模型(V1 共 5 张表)
-- Schema: user (在 Nacos 的 connection URL 通过 ?currentSchema=user 指定)
-- ============================================================================

SET TIME ZONE 'UTC';

-- btree_gist 是 EXCLUDE 约束的前置依赖(用于第三方/设备绑定的"软删后允许重绑")
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ============================================================================
-- 1. user_info — 用户主资料
-- ============================================================================
CREATE TABLE user_info (
    id                  BIGSERIAL PRIMARY KEY,              -- 内部物理主键
    user_id             BIGINT UNIQUE NOT NULL,             -- 雪花 ID,业务主键,对外暴露
    nickname            VARCHAR(64) NOT NULL DEFAULT '',
    age                 SMALLINT,                           -- 0 = 未填
    gender              SMALLINT NOT NULL DEFAULT 0,        -- 0=未知 / 1=男 / 2=女
    birthday            DATE,                               -- onboarding 一次性写入
    preferred_location  VARCHAR(128),                       -- UI 展示城市文本
    bio                 VARCHAR(500),
    profession          VARCHAR(128),                       -- UI 叫 Occupation
    education           VARCHAR(128),
    height              SMALLINT,                           -- cm
    email               VARCHAR(128),
    phone_number        VARCHAR(32),                        -- 联系方式语义,不做登录凭证

    -- 地理相关(留作后续 BH 召回使用,MVP 暂不写入)
    city_id             BIGINT,
    lat                 NUMERIC(10, 6),
    lng                 NUMERIC(10, 6),

    -- 美颜/颜值/种族(数字人标签)
    beauty_score        SMALLINT,                           -- 0-100,ai-chat 评分
    race                SMALLINT,                           -- 1=Asian 2=Black 3=Latino 4=White 5=MiddleEast 6=Indian

    -- 监管/封禁
    regulation_status   SMALLINT NOT NULL DEFAULT 0,        -- DB 原值 2=Banned / 5=Suspended
    pending             SMALLINT NOT NULL DEFAULT 1,        -- 1=placeholder(待 onboarding) / 0=已完成

    -- 兴趣召回用
    user_type           SMALLINT NOT NULL DEFAULT 1,        -- 1=BH / 2=DH,DH 由 ai-chat 注入

    last_open_at        TIMESTAMPTZ,                        -- 每次 ResolveOrCreate touch

    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE  user_info IS '用户主资料(含 last_open_at)';
COMMENT ON COLUMN user_info.user_id IS '雪花 ID,业务主键,对外暴露';
COMMENT ON COLUMN user_info.id IS '内部物理主键';
COMMENT ON COLUMN user_info.gender IS '0=未知 / 1=男 / 2=女';
COMMENT ON COLUMN user_info.profession IS 'UI 字段名为 Occupation,DB 用 profession';
COMMENT ON COLUMN user_info.regulation_status IS '0=正常 / 2=Banned / 5=Suspended';
COMMENT ON COLUMN user_info.pending IS '1=placeholder 待 onboarding / 0=完成';
COMMENT ON COLUMN user_info.user_type IS '1=BH / 2=DH';
COMMENT ON COLUMN user_info.last_open_at IS 'ResolveOrCreate 命中时更新';

-- 召回主索引(MVP 不启用,先建好)
CREATE INDEX idx_user_info_recall
    ON user_info (user_type, gender, city_id, age, beauty_score)
    WHERE deleted = 0;

CREATE INDEX idx_user_info_user_id ON user_info (user_id) WHERE deleted = 0;
CREATE INDEX idx_user_info_pending ON user_info (pending) WHERE deleted = 0 AND pending = 1;

-- ============================================================================
-- 2. user_login_phone — 手机号 ↔ userId 绑定
-- ============================================================================
CREATE TABLE user_login_phone (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    phone_e164  VARCHAR(32) NOT NULL,                      -- libphonenumber 规范化后的 E.164
    app_name    VARCHAR(32) NOT NULL,                      -- vibe / chatvibe 等
    verified_at TIMESTAMPTZ,                              -- 短信通过时间,首次绑定时 = NOW()
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT NOT NULL DEFAULT 0,

    -- (phone_e164, app_name) 唯一,允许同号在不同 App 各有用户
    CONSTRAINT uq_user_login_phone_e164_app UNIQUE (phone_e164, app_name)
);

CREATE INDEX idx_user_login_phone_user ON user_login_phone (user_id) WHERE deleted = 0;

COMMENT ON TABLE  user_login_phone IS '手机号 ↔ userId 绑定表(无软删,唯一约束生效)';
COMMENT ON COLUMN user_login_phone.phone_e164 IS 'libphonenumber 规范化后的 E.164,例如 +8613800138000';
COMMENT ON COLUMN user_login_phone.app_name IS '应用名,如 vibe / chatvibe';

-- ============================================================================
-- 3. user_third_party_registration — 第三方账号 ↔ userId 绑定
-- ============================================================================
CREATE TABLE user_third_party_registration (
    id                     BIGSERIAL PRIMARY KEY,
    user_id                BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    platform               SMALLINT NOT NULL,              -- 1=Google 2=Facebook 3=Apple
    third_party_user_id    VARCHAR(128) NOT NULL,          -- 平台返回的 sub / openid
    email                  VARCHAR(128),                   -- Google 登录带回
    app_name               VARCHAR(32) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                SMALLINT NOT NULL DEFAULT 0,

    -- 软删后允许重绑:仅对 deleted=0 行强唯一
    CONSTRAINT uq_user_third_party_active
        EXCLUDE (platform WITH =, third_party_user_id WITH =, app_name WITH =)
        WHERE (deleted = 0)
);

CREATE INDEX idx_user_third_party_user
    ON user_third_party_registration (user_id) WHERE deleted = 0;

COMMENT ON TABLE  user_third_party_registration IS '第三方账号 ↔ userId 绑定表(支持软删后重绑)';
COMMENT ON COLUMN user_third_party_registration.platform IS '1=Google / 2=Facebook / 3=Apple';

-- ============================================================================
-- 4. user_device_registration — 设备 ↔ userId 绑定(快速登录)
-- ============================================================================
CREATE TABLE user_device_registration (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    device_id   VARCHAR(128) NOT NULL,                     -- iOS IDFV / Android SSAID / Web cookie
    platform    SMALLINT NOT NULL,                         -- 1=iOS / 2=Android / 3=Web
    app_name    VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_user_device_active
        EXCLUDE (device_id WITH =, platform WITH =, app_name WITH =)
        WHERE (deleted = 0)
);

CREATE INDEX idx_user_device_user
    ON user_device_registration (user_id) WHERE deleted = 0;

COMMENT ON TABLE  user_device_registration IS '设备 ↔ userId 绑定(快速登录)';
COMMENT ON COLUMN user_device_registration.device_id IS 'iOS IDFV / Android SSAID / Web cookie';

-- ============================================================================
-- 5. user_interest — 兴趣标签
-- ============================================================================
CREATE TABLE user_interest (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    tab_key     VARCHAR(32) NOT NULL,                      -- 大类
    tag_key     VARCHAR(32) NOT NULL,                      -- 具体标签
    pic_key     VARCHAR(128),                              -- 图片标签的 object_key,可空
    sort_order  SMALLINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_interest_tab_tag UNIQUE (user_id, tab_key, tag_key)
);

CREATE INDEX idx_user_interest_user
    ON user_interest (user_id, sort_order);

COMMENT ON TABLE  user_interest IS '兴趣标签表(ReplaceUserInterests 全量替换语义)';
COMMENT ON COLUMN user_interest.pic_key IS '图片标签的 object_key,展示侧 App 自拼 URL';

-- ============================================================================
-- 触发器:user_info.updated_at 自动维护
-- (函数和触发器在 schema "user" 内,搜索路径在每条连接初始化时由 SET TIME ZONE 'UTC'
--  + Nacos connection URL 的 ?currentSchema=user 保证)
-- ============================================================================
CREATE OR REPLACE FUNCTION touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_user_info_updated_at ON user_info;
CREATE TRIGGER trg_user_info_updated_at
    BEFORE UPDATE ON user_info
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

DROP TRIGGER IF EXISTS trg_user_device_updated_at ON user_device_registration;
CREATE TRIGGER trg_user_device_updated_at
    BEFORE UPDATE ON user_device_registration
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
