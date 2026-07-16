package com.dating.gateway.exception;

import lombok.Getter;

@Getter
public class GatewayException extends RuntimeException {
    private final int code;

    public GatewayException(int code, String message) {
        super(message);
        this.code = code;
    }

    public GatewayException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
