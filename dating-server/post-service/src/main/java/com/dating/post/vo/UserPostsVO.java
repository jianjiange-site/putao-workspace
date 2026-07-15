package com.dating.post.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 用户帖子列表响应.
 */
@Data
@Builder
public class UserPostsVO {

    /** 帖子列表 */
    private List<PostDetailVO> items;

    /** 下一页游标(0表示无更多) */
    private Long nextCursor;

    /** 是否有更多 */
    private Boolean hasMore;
}
