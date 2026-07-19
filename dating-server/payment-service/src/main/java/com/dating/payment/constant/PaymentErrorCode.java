package com.dating.payment.constant;

/**
 * 业务错误码定义.
 *
 * <p>区段划分：
 * <ul>
 *   <li>0           - 成功</li>
 *   <li>2001~2099   - PayPal 通道</li>
 *   <li>3001~3099   - 金币模块</li>
 *   <li>4001~4099   - 通用参数/业务校验</li>
 *   <li>5001~5099   - 提现模块</li>
 *   <li>9999        - 未实现占位</li>
 * </ul>
 */
public final class PaymentErrorCode {

    /** 成功 */
    public static final int OK = 0;

    /** PayPal 下单失败 */
    public static final int PAYPAL_CREATE_ORDER_FAILED = 2001;
    /** PayPal capture 失败 */
    public static final int PAYPAL_CAPTURE_FAILED = 2002;
    /** PayPal webhook 验签失败 */
    public static final int PAYPAL_VERIFY_SIGNATURE_FAILED = 2003;
    /** PayPal webhook 处理失败 */
    public static final int PAYPAL_WEBHOOK_HANDLER_FAILED = 2004;

    /** 金币不足 */
    public static final int INSUFFICIENT_COINS = 3001;
    /** 金币账户未初始化 */
    public static final int COIN_ACCOUNT_NOT_FOUND = 3002;

    /** 缺少必填参数 */
    public static final int MISSING_PARAMETER = 4001;
    /** 订单不存在 */
    public static final int ORDER_NOT_FOUND = 4002;
    /** 商品不存在 */
    public static final int PRODUCT_NOT_FOUND = 4003;
    /** 支付通道不支持 */
    public static final int CHANNEL_NOT_SUPPORTED = 4004;

    /** 提现账户未绑定 */
    public static final int WITHDRAW_ACCOUNT_NOT_BOUND = 5001;
    /** 提现余额不足 */
    public static final int WITHDRAW_INSUFFICIENT_BALANCE = 5002;

    /** 占位:通道或接口未实现 */
    public static final int NOT_IMPLEMENTED = 9999;

    private PaymentErrorCode() {
    }

    public static String getMessage(int code) {
        return switch (code) {
            case OK -> "OK";
            case PAYPAL_CREATE_ORDER_FAILED -> "PayPal createOrder failed";
            case PAYPAL_CAPTURE_FAILED -> "PayPal capture failed";
            case PAYPAL_VERIFY_SIGNATURE_FAILED -> "PayPal verify-webhook-signature failed";
            case PAYPAL_WEBHOOK_HANDLER_FAILED -> "PayPal webhook handler failed";
            case INSUFFICIENT_COINS -> "Insufficient coins";
            case COIN_ACCOUNT_NOT_FOUND -> "Coin account not found";
            case MISSING_PARAMETER -> "Missing required parameter";
            case ORDER_NOT_FOUND -> "Order not found";
            case PRODUCT_NOT_FOUND -> "Product not found";
            case CHANNEL_NOT_SUPPORTED -> "Payment channel not supported";
            case WITHDRAW_ACCOUNT_NOT_BOUND -> "Withdraw account not bound";
            case WITHDRAW_INSUFFICIENT_BALANCE -> "Withdraw insufficient balance";
            case NOT_IMPLEMENTED -> "NOT_IMPLEMENTED";
            default -> "Unknown error code: " + code;
        };
    }
}
