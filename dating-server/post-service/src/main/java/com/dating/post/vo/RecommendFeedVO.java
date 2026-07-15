package com.dating.post.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 推荐 Feed 响应.
 */
@Data
@Builder
public class RecommendFeedVO {

    /** 帖子列表 */
    private List<PostDetailVO> items;

    /** 下一页游标 */
    private String nextCursor;

    /** 是否有更多 */
    private Boolean hasMore;
}
