package com.dating.payment.constant;

/**
 * 支付通道枚举(字符串与 DB 列 payment_channel 对齐).
 */
public final class PaymentChannel {

    public static final String PAYPAL = "PAYPAL";
    public static final String APPLE_IAP = "APPLE_IAP";
    public static final String GOOGLE_BILLING = "GOOGLE_BILLING";
    public static final String STRIPE = "STRIPE";

    private PaymentChannel() {
    }
}
