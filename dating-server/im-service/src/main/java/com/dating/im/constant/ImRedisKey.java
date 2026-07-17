package com.dating.im.constant;

/**
 * Redis Key 定义.
 *
 * <p>遵循 putao:<service>:<domain>:<id> 格式.
 */
public final class ImRedisKey {

    private ImRedisKey() {}

    /** 在线用户 ZSet, member=userId, score=上线时刻(epoch ms) */
    public static final String PRESENCE_ONLINE = "putao:im:presence:online";

    /**
     * 在线用户 ZSet Key.
     */
    public static String presenceOnline() {
        return PRESENCE_ONLINE;
    }
}
