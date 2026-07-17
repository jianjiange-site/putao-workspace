package com.dating.gateway.exception;

public final class ErrorCodes {

    private ErrorCodes() {}

    public static final int SMS_RATE_LIMITED = 10002;
    public static final int INVALID_SMS_CODE = 10003;
    public static final int INVALID_REFRESH_TOKEN = 10004;

    public static final int PROFILE_NOT_FOUND = 10101;
    public static final int PROFILE_UPDATE_FAILED = 10102;
    public static final int PROFILE_GET_USERS_FAILED = 10103;

    public static final int MATCH_SWIPE_FAILED = 10301;
    public static final int MATCH_SUPER_HI_FAILED = 10302;
    public static final int MATCH_LIST_FAILED = 10303;
    public static final int MATCH_HISTORY_FAILED = 10304;

    public static final int HOME_CARDS_FAILED = 10401;

    public static final int POST_OPERATION_FAILED = 10901;

    public static final int IM_TOKEN_FAILED = 10601;
    public static final int CALL_TOKEN_FAILED = 10602;

    public static final int INTERNAL_ERROR = 50000;
    public static final int UNAUTHORIZED = 40100;
    public static final int FORBIDDEN = 40300;
}
