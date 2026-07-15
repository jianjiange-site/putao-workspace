package com.dating.post.exception;

import com.dating.post.constant.ErrorCode;

/**
 * 权限不足异常.
 */
public class ForbiddenException extends BizException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
