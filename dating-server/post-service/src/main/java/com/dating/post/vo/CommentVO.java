package com.dating.post.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 评论 VO.
 */
@Data
@Builder
public class CommentVO {

    /** 评论ID */
    private Long commentId;

    /** 帖子ID */
    private Long postId;

    /** 用户ID */
    private Long userId;

    /** 根评论ID */
    private Long rootId;

    /** 直接父评论ID */
    private Long parentId;

    /** 被回复人用户ID */
    private Long replyToUserId;

    /** 内容 */
    private String content;

    /** 创建时间戳(秒) */
    private Long createdAt;
}
