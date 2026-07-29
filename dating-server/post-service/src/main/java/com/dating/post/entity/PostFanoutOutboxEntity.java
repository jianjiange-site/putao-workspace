package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 发帖写扩散 Outbox.
 */
@Data
@TableName("post_fanout_outbox")
public class PostFanoutOutboxEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long postId;
    private Long authorUserId;
    private Long createdAtEpoch;
    private String status;
    private Integer attempts;
    private Instant nextRetryAt;
    private Instant createdAt;
    private Instant deliveredAt;
}
