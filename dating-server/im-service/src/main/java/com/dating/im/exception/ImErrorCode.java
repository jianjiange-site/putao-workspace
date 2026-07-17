package com.dating.im.exception;

/**
 * IM 服务异常码.
 */
public final class ImErrorCode {

    private ImErrorCode() {}

    /** 成功 */
    public static final int OK = 0;

    /** 内部错误 */
    public static final int INTERNAL_ERROR = 1001;

    /** 用户不存在 */
    public static final int USER_NOT_FOUND = 1002;

    /** 会话不存在 */
    public static final int CONVERSATION_NOT_FOUND = 1003;

    /** 消息不存在 */
    public static final int MESSAGE_NOT_FOUND = 1004;

    /** 参数无效 */
    public static final int INVALID_PARAMETER = 1005;

    /** 检测到站外联系方式,拒发 */
    public static final int REJECT_CONTACT_INFO = 5002;

    /** 金币不足 */
    public static final int REJECT_INSUFFICIENT_COINS = 5003;

    /** payment 服务不可用 */
    public static final int REJECT_PAYMENT_UNAVAILABLE = 5004;

    public static String getMessage(int code) {
        return switch (code) {
            case OK -> "Success";
            case INTERNAL_ERROR -> "Internal error";
            case USER_NOT_FOUND -> "User not found";
            case CONVERSATION_NOT_FOUND -> "Conversation not found";
            case MESSAGE_NOT_FOUND -> "Message not found";
            case INVALID_PARAMETER -> "Invalid parameter";
            case REJECT_CONTACT_INFO -> "Contact info detected";
            case REJECT_INSUFFICIENT_COINS -> "Insufficient coins";
            case REJECT_PAYMENT_UNAVAILABLE -> "Payment service unavailable";
            default -> "Unknown error";
        };
    }
}
