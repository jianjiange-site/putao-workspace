package com.dating.example.exception;

import com.dating.example.constant.ErrorCode;
import lombok.Getter;

/**
 * Business Exception Base Class.
 *
 * <p>All business exceptions should extend this class.
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
