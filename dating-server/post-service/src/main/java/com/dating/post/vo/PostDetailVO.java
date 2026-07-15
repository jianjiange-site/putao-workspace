package com.dating.post.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 帖子详情 VO.
 */
@Data
@Builder
public class PostDetailVO {

    /** 帖子ID */
    private Long postId;

    /** 用户ID */
    private Long userId;

    /** 内容 */
    private String content;

    /** 图片key列表 */
    private List<String> imageKeys;

    /** 点赞数 */
    private Integer likeCount;

    /** 评论数 */
    private Integer commentCount;

    /** 是否已点赞 */
    private Boolean isLiked;

    /** 创建时间戳(秒) */
    private Long createdAt;
}
