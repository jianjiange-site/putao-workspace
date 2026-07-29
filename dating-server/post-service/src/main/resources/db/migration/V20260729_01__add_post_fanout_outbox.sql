CREATE TABLE post_fanout_outbox (
    id               BIGSERIAL PRIMARY KEY,
    event_id         VARCHAR(64) UNIQUE NOT NULL,
    post_id          BIGINT NOT NULL,
    author_user_id   BIGINT NOT NULL,
    created_at_epoch BIGINT NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts         INT NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX idx_post_fanout_outbox_due
    ON post_fanout_outbox(status, next_retry_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE post_fanout_outbox
    IS '发帖事务内写入的好友写扩散可靠投递 Outbox';
