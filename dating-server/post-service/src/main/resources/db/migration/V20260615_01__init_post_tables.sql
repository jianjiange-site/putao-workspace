-- =============================================================
-- Post Service Database Schema
-- Version: V20260615_01__init_post_tables.sql
-- Description: Initialize 5 tables for post-service
-- =============================================================

-- 1. posts — 帖子主表
CREATE TABLE posts (
    id              BIGSERIAL PRIMARY KEY,
    post_id         BIGINT UNIQUE NOT NULL,
    user_id         BIGINT NOT NULL,
    content         VARCHAR(1024) NOT NULL,
    status          SMALLINT DEFAULT 1,       -- 0=已删 / 1=正常 / 2=审核中
    deleted         SMALLINT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 索引: 用户帖子列表分页
CREATE INDEX idx_posts_user_created ON posts(user_id, created_at DESC);
-- 索引: Feed 池重建捞近3天数据
CREATE INDEX idx_posts_created_at ON posts(created_at DESC);

COMMENT ON TABLE posts IS '帖子主表';
COMMENT ON COLUMN posts.post_id IS '雪花ID,跨库稳定的业务主键';
COMMENT ON COLUMN posts.status IS '0=已删 / 1=正常 / 2=审核中';
COMMENT ON COLUMN posts.deleted IS '逻辑删除标记';

-- 2. post_images — 帖子图片
CREATE TABLE post_images (
    post_id         BIGINT NOT NULL,
    sort_order      SMALLINT NOT NULL,
    image_key       VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, sort_order)
);

COMMENT ON TABLE post_images IS '帖子图片表';
COMMENT ON COLUMN post_images.image_key IS '对象存储key,不存URL';

-- 3. post_stats — 计数底座
CREATE TABLE post_stats (
    post_id         BIGINT PRIMARY KEY,
    like_count      INT DEFAULT 0,
    comment_count   INT DEFAULT 0,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 索引: 未来「最热」榜单
CREATE INDEX idx_post_stats_like_count ON post_stats(like_count DESC);
CREATE INDEX idx_post_stats_comment_count ON post_stats(comment_count DESC);

COMMENT ON TABLE post_stats IS '帖子计数底座(已刷盘部分)';

-- 4. post_likes — 点赞幂等记录
CREATE TABLE post_likes (
    user_id         BIGINT NOT NULL,
    post_id         BIGINT NOT NULL,
    status          SMALLINT DEFAULT 1,       -- 1=已赞 / 0=已取消
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
);

-- 索引: 反查「谁赞了这帖」
CREATE INDEX idx_post_likes_post_id ON post_likes(post_id) WHERE status = 1;

COMMENT ON TABLE post_likes IS '点赞幂等记录';

-- 5. post_comments — 评论(预留楼中楼)
CREATE TABLE post_comments (
    id              BIGSERIAL PRIMARY KEY,
    comment_id      BIGINT UNIQUE NOT NULL,
    post_id         BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    root_id         BIGINT DEFAULT 0,         -- 根评论ID(自身是根则为0)
    parent_id       BIGINT DEFAULT 0,         -- 直接父评论ID
    reply_to_user_id BIGINT DEFAULT 0,        -- 被回复人
    content         VARCHAR(512) NOT NULL,
    status          SMALLINT DEFAULT 1,
    deleted         SMALLINT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 索引: 一级评论分页(root_id = 0)
CREATE INDEX idx_post_comments_post_root ON post_comments(post_id, root_id, created_at DESC);
-- 索引: 楼中楼按时间正序展开
CREATE INDEX idx_post_comments_root ON post_comments(root_id, created_at ASC);

COMMENT ON TABLE post_comments IS '评论表,预留楼中楼扩展';
COMMENT ON COLUMN post_comments.root_id IS '根评论ID(自身是根则为0)';
COMMENT ON COLUMN post_comments.parent_id IS '直接父评论ID';
COMMENT ON COLUMN post_comments.reply_to_user_id IS '被回复人user_id';

-- 6. shedlock — 定时任务多实例互斥(ShedLock JDBC Provider)
CREATE TABLE shedlock (
    name            VARCHAR(64) PRIMARY KEY,
    lock_until     TIMESTAMPTZ NOT NULL,
    locked_at      TIMESTAMPTZ NOT NULL,
    locked_by      VARCHAR(255) NOT NULL
);

COMMENT ON TABLE shedlock IS 'ShedLock定时任务锁表';
