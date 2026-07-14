package com.dating.example.exception;

import com.dating.example.constant.ErrorCode;
import lombok.Getter;

/**
 * Example Not Found Exception.
 *
 * <p>Thrown when an example entity is not found.
 */
@Getter
public class ExampleNotFoundException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Long exampleId;

    public ExampleNotFoundException(Long exampleId) {
        super("Example not found: " + exampleId);
        this.errorCode = ErrorCode.EXAMPLE_NOT_FOUND;
        this.exampleId = exampleId;
    }
}
