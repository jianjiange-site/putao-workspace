package com.dating.payment.constant;

/**
 * 提现状态.
 */
public final class WithdrawStatus {

    public static final String INIT = "INIT";
    public static final String AUDITING = "AUDITING";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";

    private WithdrawStatus() {
    }
}
