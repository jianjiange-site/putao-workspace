package com.dating.match.exception;

import lombok.Getter;

/**
 * Match 业务异常.
 *
 * <p>所有 match 模块的业务异常继承此类.
 */
@Getter
public class MatchBizException extends RuntimeException {

    private final int code;

    public MatchBizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public MatchBizException(int code) {
        super(String.valueOf(code));
        this.code = code;
    }
}
