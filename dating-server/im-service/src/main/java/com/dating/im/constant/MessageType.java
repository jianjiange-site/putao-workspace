package com.dating.im.constant;

/**
 * 消息类型枚举.
 */
public final class MessageType {

    private MessageType() {}

    /** 文本消息 */
    public static final int TEXT = 1;

    /** 图片消息 */
    public static final int IMAGE = 2;

    /** 语音消息 */
    public static final int AUDIO = 3;

    /** 视频消息 */
    public static final int VIDEO = 4;

    /** 礼物消息 */
    public static final int GIFT = 5;

    /** 系统消息 */
    public static final int SYSTEM = 10;

    public static String fromCode(int code) {
        return switch (code) {
            case TEXT -> "TEXT";
            case IMAGE -> "IMAGE";
            case AUDIO -> "AUDIO";
            case VIDEO -> "VIDEO";
            case GIFT -> "GIFT";
            case SYSTEM -> "SYSTEM";
            default -> "UNKNOWN";
        };
    }

    public static int fromString(String type) {
        return switch (type.toUpperCase()) {
            case "TEXT" -> TEXT;
            case "IMAGE" -> IMAGE;
            case "AUDIO" -> AUDIO;
            case "VIDEO" -> VIDEO;
            case "GIFT" -> GIFT;
            case "SYSTEM" -> SYSTEM;
            default -> TEXT;
        };
    }
}
