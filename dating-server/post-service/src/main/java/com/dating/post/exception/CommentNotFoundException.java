package com.dating.post.exception;

import com.dating.post.constant.ErrorCode;

/**
 * 评论不存在异常.
 */
public class CommentNotFoundException extends BizException {

    public CommentNotFoundException() {
        super(ErrorCode.COMMENT_NOT_FOUND);
    }

    public CommentNotFoundException(long commentId) {
        super(ErrorCode.COMMENT_NOT_FOUND, "Comment not found: " + commentId);
    }
}
