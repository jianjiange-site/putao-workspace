package com.dating.user.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码.
 *
 * <p>user-service 独占 10001–10499 段(与 mobile-gateway 10500+ 段不冲突),
 * 段位细分见 doc/specs/user-service-design.md §5.8.
 *
 * <p>gRPC 侧: 业务异常由 GrpcExceptionAdvice 转 io.grpc.StatusRuntimeException,
 * 客户端拿 Status 码 + description 还原; 码值仅用于 description 字符串拼接,
 * 不参与 gRPC Status.Code 的判定.
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "Success"),

    // ========== 通用段 ==========
    BAD_REQUEST(400, "Bad request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not found"),
    BATCH_SIZE_EXCEEDED(429, "Batch size exceeded limit"),
    INTERNAL_ERROR(500, "Internal server error"),

    // ========== 100xx 用户 ==========
    USER_NOT_FOUND(10001, "User not found"),
    USER_BANNED(10002, "User is banned"),
    USER_SUSPENDED(10003, "User is suspended"),
    OPERATIONAL_BANNED(10004, "User is banned operationally"),

    // ========== 101xx 头像 ==========
    AVATAR_EXT_INVALID(10101, "Avatar extension must be one of jpg/jpeg/png/webp"),
    AVATAR_SIZE_EXCEEDED(10102, "Avatar size exceeds 10MB limit"),
    AVATAR_OBJECT_KEY_MISMATCH(10103, "Avatar object key prefix mismatch"),
    AVATAR_OBJECT_NOT_FOUND(10104, "Avatar object not found in storage"),
    AVATAR_PRESIGN_FAILED(10105, "Failed to generate presigned URL"),

    // ========== 102xx 兴趣 ==========
    INTEREST_PIC_LIMIT_EXCEEDED(10201, "Interest picture tags cannot exceed 9"),
    INTEREST_TEXT_LIMIT_EXCEEDED(10202, "Interest text tags cannot exceed 50"),
    INTEREST_PIC_EXCEEDED(10203, "Interest picture tags cannot exceed 9"),
    INTEREST_TAGS_EXCEEDED(10204, "Interest tags cannot exceed 50"),

    // ========== 103xx 身份解析 ==========
    PHONE_INVALID(10301, "Phone number is invalid (E.164 normalization failed)"),
    PHONE_BLACKLISTED(10302, "Phone number is blacklisted"),

    // ========== 104xx 批量 / Onboarding ==========
    BATCH_TOO_LARGE(10401, "Batch size exceeds maximum of 200"),
    ONBOARDING_GENDER_REQUIRED(10410, "Onboarding gender is required"),
    ONBOARDING_BIRTHDAY_REQUIRED(10411, "Onboarding birthday is required");

    private final int code;
    private final String message;
}
