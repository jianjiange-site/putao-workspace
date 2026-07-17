package com.dating.user.exception;

import com.dating.user.constant.ErrorCode;
import lombok.Getter;

/**
 * 业务异常基类.
 *
 * <p>由 gRPC 入口的 {@link GrpcExceptionAdvice} 捕获并转换为
 * {@code io.grpc.StatusRuntimeException},通过 description 携带 ErrorCode
 * 数值(便于网关侧解析还原).
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 描述串携带 code 前缀,例如 "[10001] User not found" */
    public String getGrpcDescription() {
        return "[" + errorCode.getCode() + "] " + getMessage();
    }
}
