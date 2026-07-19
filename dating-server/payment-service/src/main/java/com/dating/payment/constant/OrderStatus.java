package com.dating.payment.constant;

/**
 * 订单状态机常量.
 *
 * <p>与 {@code proto/payment/payment.proto} 中 OrderStatus 对齐.
 */
public final class OrderStatus {

    public static final String INIT = "INIT";
    public static final String PAID = "PAID";
    public static final String GRANTED = "GRANTED";
    public static final String FAILED = "FAILED";

    private OrderStatus() {
    }
}
