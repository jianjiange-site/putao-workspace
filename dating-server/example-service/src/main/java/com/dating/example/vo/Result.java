package com.dating.example.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result Wrapper.
 *
 * <p>Standard response wrapper for all REST endpoints.
 *
 * @param <T> data type
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok() {
        return new Result<>(200, "Success", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "Success", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }
}
