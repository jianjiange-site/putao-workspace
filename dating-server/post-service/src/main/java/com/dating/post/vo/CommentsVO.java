package com.dating.post.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 评论列表响应.
 */
@Data
@Builder
public class CommentsVO {

    /** 评论列表 */
    private List<CommentVO> comments;

    /** 下一页游标(0表示无更多) */
    private Long nextCursor;

    /** 是否有更多 */
    private Boolean hasMore;
}
