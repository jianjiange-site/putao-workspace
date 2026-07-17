package com.dating.gateway.exception;

import com.dating.gateway.vo.Result;

public class GlobalExceptionHandler {

    public Result<Void> handleGatewayException(GatewayException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    public Result<Void> handleException(Exception e) {
        return Result.fail(50000, "Internal server error: " + e.getMessage());
    }
}
