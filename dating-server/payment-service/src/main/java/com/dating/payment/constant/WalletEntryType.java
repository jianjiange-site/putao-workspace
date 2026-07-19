package com.dating.payment.constant;

/**
 * 钱包流水类型.
 */
public final class WalletEntryType {

    public static final String INCOME = "INCOME";
    public static final String WITHDRAW_FREEZE = "WITHDRAW_FREEZE";
    public static final String WITHDRAW_SUCCESS = "WITHDRAW_SUCCESS";
    public static final String WITHDRAW_FAIL = "WITHDRAW_FAIL";
    public static final String ADMIN_ADJUST = "ADMIN_ADJUST";

    private WalletEntryType() {
    }
}
