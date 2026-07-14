package com.dating.example.exception;

import com.dating.example.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global Exception Handler.
 *
 * <p>Handles business exceptions and converts them to standard responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle business exception.
     */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBizException(BizException e) {
        log.warn("Business exception: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /**
     * Handle validation exception.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Validation failed";
        log.warn("Validation exception: {}", message);
        return Result.fail(400, message);
    }

    /**
     * Handle bind exception.
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "Binding failed";
        log.warn("Bind exception: {}", message);
        return Result.fail(400, message);
    }

    /**
     * Handle generic exception.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return Result.fail(500, "Internal server error");
    }
}
