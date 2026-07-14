package com.dating.example.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Error Code Enum.
 *
 * <p>Defines all business error codes.
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(200, "Success"),
    BAD_REQUEST(400, "Bad request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not found"),
    INTERNAL_ERROR(500, "Internal server error"),

    // Example service errors
    EXAMPLE_NOT_FOUND(1001, "Example not found"),
    EXAMPLE_ALREADY_EXISTS(1002, "Example already exists"),
    ;

    private final int code;
    private final String message;
}
