package com.dating.im.exception;

/**
 * 业务异常基类.
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code) {
        super(ImErrorCode.getMessage(code));
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
