package com.dating.user.exception;

import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * gRPC 全局异常处理器.
 *
 * <p>把业务异常映射到合适的 gRPC Status.Code:
 * <ul>
 *   <li>USER_NOT_FOUND → NOT_FOUND</li>
 *   <li>USER_BANNED / SUSPENDED → PERMISSION_DENIED</li>
 *   <li>参数非法 → INVALID_ARGUMENT</li>
 *   <li>其他业务异常 → FAILED_PRECONDITION(语义: 调用方前置条件不满足)</li>
 *   <li>兜底异常 → INTERNAL</li>
 * </ul>
 *
 * <p>ErrorCode 数值编码在 description 中(由 BizException.getGrpcDescription() 提供),
 * 网关侧 GrpcExceptionAdvice 还原成 Result&lt;T&gt;.
 */
@Slf4j
@Component
@GrpcAdvice
public class GrpcExceptionAdvice {

    @GrpcExceptionHandler(UserNotFoundException.class)
    public Status handleUserNotFound(UserNotFoundException e) {
        log.warn("user not found: {}", e.getMessage());
        return Status.NOT_FOUND.withDescription(e.getGrpcDescription());
    }

    @GrpcExceptionHandler(UserBannedException.class)
    public Status handleBanned(UserBannedException e) {
        log.warn("user banned: {}", e.getMessage());
        return Status.PERMISSION_DENIED.withDescription(e.getGrpcDescription());
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status handleIllegalArgument(IllegalArgumentException e) {
        log.warn("illegal argument: {}", e.getMessage());
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    @GrpcExceptionHandler(BizException.class)
    public Status handleBiz(BizException e) {
        log.warn("biz exception: code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return Status.FAILED_PRECONDITION.withDescription(e.getGrpcDescription());
    }

    @GrpcExceptionHandler(Exception.class)
    public Status handleAny(Exception e) {
        log.error("unexpected exception", e);
        return Status.INTERNAL.withDescription("Internal server error");
    }
}
