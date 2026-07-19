package com.dating.payment.exception;

import lombok.Getter;

/**
 * 支付业务异常.
 *
 * <p>所有支付模块的业务异常继承此类.
 */
@Getter
public class PaymentBizException extends RuntimeException {

    private final int code;

    public PaymentBizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public PaymentBizException(int code) {
        super(String.valueOf(code));
        this.code = code;
    }
}
