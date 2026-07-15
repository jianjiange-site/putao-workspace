package com.dating.post.exception;

import com.dating.post.constant.ErrorCode;

/**
 * 帖子不存在异常.
 */
public class PostNotFoundException extends BizException {

    public PostNotFoundException() {
        super(ErrorCode.POST_NOT_FOUND);
    }

    public PostNotFoundException(long postId) {
        super(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
    }
}
