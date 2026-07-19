package com.dating.payment.constant;

/**
 * 订阅档位常量.
 *
 * <p>档位与 {@code SubscriptionTier} proto enum 对齐,数值用于 {@code user_subscription.tier}.
 */
public final class SubscriptionTierConst {

    public static final int FREE = 1;
    public static final int WEEKLY = 2;
    public static final int MONTHLY = 3;
    public static final int YEARLY = 4;

    private SubscriptionTierConst() {
    }
}
