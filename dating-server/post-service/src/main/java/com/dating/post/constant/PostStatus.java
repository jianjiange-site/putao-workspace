package com.dating.post.constant;

/**
 * 帖子状态常量.
 */
public final class PostStatus {

    private PostStatus() {}

    /** 已删除 */
    public static final int DELETED = 0;

    /** 正常 */
    public static final int NORMAL = 1;

    /** 审核中 */
    public static final int PENDING_REVIEW = 2;
}
