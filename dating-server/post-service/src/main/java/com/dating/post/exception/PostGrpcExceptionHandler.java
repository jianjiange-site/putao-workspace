package com.dating.post.exception;

import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * gRPC 全局异常处理器.
 *
 * <p>使用 grpc-spring-boot-starter 2.15.0 的 {@link GrpcAdvice} 机制,
 * 把业务异常转换为对应的 gRPC Status,客户端通过 status code 判断失败原因.
 */
@Slf4j
@Component
@GrpcAdvice
public class PostGrpcExceptionHandler {

    /**
     * 处理帖子不存在异常.
     */
    @GrpcExceptionHandler(PostNotFoundException.class)
    public Status handlePostNotFound(PostNotFoundException e) {
        log.warn("Post not found: {}", e.getMessage());
        return Status.NOT_FOUND.withDescription(e.getMessage());
    }

    /**
     * 处理评论不存在异常.
     */
    @GrpcExceptionHandler(CommentNotFoundException.class)
    public Status handleCommentNotFound(CommentNotFoundException e) {
        log.warn("Comment not found: {}", e.getMessage());
        return Status.NOT_FOUND.withDescription(e.getMessage());
    }

    /**
     * 处理权限异常.
     */
    @GrpcExceptionHandler(ForbiddenException.class)
    public Status handleForbidden(ForbiddenException e) {
        log.warn("Forbidden: {}", e.getMessage());
        return Status.PERMISSION_DENIED.withDescription(e.getMessage());
    }

    /**
     * 处理通用业务异常.
     */
    @GrpcExceptionHandler(BizException.class)
    public Status handleBizException(BizException e) {
        log.warn("Business exception: code={}, message={}",
                e.getErrorCode().getCode(), e.getMessage());
        return Status.INTERNAL.withDescription(e.getMessage());
    }

    /**
     * 处理参数校验异常.
     */
    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    /**
     * 处理兜底异常.
     */
    @GrpcExceptionHandler(Exception.class)
    public Status handleException(Exception e) {
        log.error("Unexpected exception", e);
        return Status.INTERNAL.withDescription("Internal server error");
    }
}