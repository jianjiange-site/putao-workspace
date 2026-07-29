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

    /** 用户类型缓存前缀, member=userId, value=0(BH)/1(DH) */
    public static final String USER_TYPE_PREFIX = "putao:im:user:type:";

    /**
     * 在线用户 ZSet Key.
     */
    public static String presenceOnline() {
        return PRESENCE_ONLINE;
    }

    /**
     * 用户类型缓存 Key.
     */
    public static String userType(Long userId) {
        return USER_TYPE_PREFIX + userId;
    }
}
