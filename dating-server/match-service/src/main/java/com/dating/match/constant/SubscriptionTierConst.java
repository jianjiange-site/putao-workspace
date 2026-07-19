package com.dating.match.constant;

/**
 * 订阅档位与配额常量.
 *
 * <p>档位数值与 payment-service SubscriptionTierConst 对齐.
 */
public final class SubscriptionTierConst {

    public static final int FREE = 1;
    public static final int WEEKLY = 2;
    public static final int MONTHLY = 3;
    public static final int YEARLY = 4;

    /** Super Hi 用金币单价 */
    public static final int SUPER_HI_COIN_PRICE = 100;

    private SubscriptionTierConst() {
    }

    /**
     * 订阅赠送的每日 Super Hi 上限(0 表示不送).
     */
    public static int dailySuperHiLimit(int tier) {
        return switch (tier) {
            case MONTHLY, YEARLY -> 1;
            default -> 0;
        };
    }

    /**
     * 每日右划次数上限.
     */
    public static int dailyRightSwipeLimit(int tier) {
        return switch (tier) {
            case FREE -> 5;
            case WEEKLY -> 10;
            case MONTHLY, YEARLY -> 15;
            default -> 5;
        };
    }

    /**
     * 每日可划卡片上限(左划 + 右划 + Super Hi).
     */
    public static int dailyCardLimit(int tier) {
        return switch (tier) {
            case FREE -> 50;
            case WEEKLY -> 80;
            case MONTHLY, YEARLY -> 120;
            default -> 50;
        };
    }
}
