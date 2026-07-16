package com.dating.gateway.exception;

/** Error Codes for Mobile Gateway. */
public final class ErrorCodes {
    private ErrorCodes() {}

    // Token related (105xx)
    public static final int TOKEN_INVALID = 10501;
    public static final int TOKEN_EXPIRED = 10502;
    public static final int TOKEN_REVOKED = 10503;
    public static final int REFRESH_TOKEN_REUSED = 10504;
    public static final int REFRESH_TOKEN_DEVICE_MISMATCH = 10505;

    // SMS/Third-party related (106xx)
    public static final int SMS_CODE_INVALID = 10601;
    public static final int SMS_CODE_EXPIRED = 10602;
    public static final int THIRD_PARTY_TOKEN_INVALID = 10603;

    // Upstream/External service related (109xx)
    public static final int UPSTREAM_UNAVAILABLE = 10901;
}
