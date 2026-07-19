package com.dating.payment.constant;

/**
 * Redis Key 统一前缀规范:
 * <pre>putao:payment:&lt;domain&gt;:&lt;id&gt;</pre>
 */
public final class PaymentRedisKey {

    /** 前缀 */
    public static final String PREFIX = "putao:payment";

    /** 幂等键缓存(扣费/发币防重) */
    public static final String IDEMPOTENT = PREFIX + ":idempotent:%s:%s";
    /** 订单号锁 */
    public static final String ORDER_LOCK = PREFIX + ":order:lock:%s";
    /** 订单缓存 */
    public static final String ORDER = PREFIX + ":order:%s";
    /** 金币账户缓存 */
    public static final String COIN_ACCOUNT = PREFIX + ":coin:account:%s";
    /** 订阅缓存 */
    public static final String SUBSCRIPTION = PREFIX + ":subscription:%s";

    private PaymentRedisKey() {
    }

    /**
     * 构造幂等缓存 key.
     *
     * @param domain 业务域, e.g. "consume" / "add-paid"
     * @param key    业务幂等键
     * @return 完整 key
     */
    public static String idempotent(String domain, String key) {
        return String.format(IDEMPOTENT, domain, key);
    }

    public static String order(String orderId) {
        return String.format(ORDER, orderId);
    }

    public static String orderLock(String orderId) {
        return String.format(ORDER_LOCK, orderId);
    }

    public static String coinAccount(Long userId) {
        return String.format(COIN_ACCOUNT, userId);
    }

    public static String subscription(Long userId) {
        return String.format(SUBSCRIPTION, userId);
    }
}
