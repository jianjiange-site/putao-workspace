package com.dating.post.exception;

import com.dating.post.constant.ErrorCode;
import lombok.Getter;

/**
 * 业务异常基类.
 *
 * <p>所有业务异常应该继承此类.
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
