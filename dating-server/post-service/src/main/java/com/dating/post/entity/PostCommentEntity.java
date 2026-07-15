package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * PostComment Entity — 评论表(预留楼中楼).
 *
 * <p>业务主键: comment_id
 * 初期所有评论都是 root_id = parent_id = reply_to_user_id = 0
 */
@Data
@TableName("post_comments")
public class PostCommentEntity {

    /** 内部物理主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务主键,对外暴露 */
    private Long commentId;

    /** 帖子ID */
    private Long postId;

    /** 评论用户ID */
    private Long userId;

    /** 根评论ID(自身是根则为0) */
    private Long rootId;

    /** 直接父评论ID */
    private Long parentId;

    /** 被回复人user_id */
    private Long replyToUserId;

    /** 评论内容 */
    private String content;

    /** 状态 */
    private Integer status;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
