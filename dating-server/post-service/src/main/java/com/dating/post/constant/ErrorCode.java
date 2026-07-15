package com.dating.post.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Error Code Enum.
 *
 * <p>定义 post-service 所有业务错误码.
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "Success"),

    // 通用错误
    BAD_REQUEST(400, "Bad request"),

    // 内容相关 4001-4009
    CONTENT_EMPTY(4001, "Content cannot be empty"),
    CONTENT_TOO_LONG(4002, "Content exceeds maximum length of 1024 characters"),
    IMAGE_COUNT_EXCEEDED(4003, "Image count exceeds maximum of 9"),
    IMAGE_KEY_EMPTY(4004, "Image key cannot be empty"),

    // 资源不存在 4005-4009
    POST_NOT_FOUND(4005, "Post not found"),
    COMMENT_NOT_FOUND(4006, "Comment not found"),

    // 评论相关 4010-4019
    COMMENT_CONTENT_EMPTY(4007, "Comment content cannot be empty"),
    COMMENT_CONTENT_TOO_LONG(4008, "Comment content exceeds maximum length of 512 characters"),

    // 权限相关 4030-4039
    FORBIDDEN(4030, "Permission denied"),

    // 内部错误 5000
    INTERNAL_ERROR(5000, "Internal server error"),
    ;

    private final int code;
    private final String message;
}
