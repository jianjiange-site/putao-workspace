package com.dating.user.constant;

/**
 * 缓存 Key 命名空间 — 与 CLAUDE.md 规范一致:{@code <service>:<domain>:<id>}.
 *
 * <p>本服务全部以 {@code user:} 前缀,通过 {@link #setPrefix(String)} 在启动时
 * 从 Nacos / yml 的 {@code app.cache.key-prefix} 注入(默认 {@code putao:user}).
 *
 * <p>设计依据: doc/specs/user-service-design.md §5.5
 */
public final class RedisKey {

    private RedisKey() {}

    /** 默认 key 前缀 */
    public static final String DEFAULT_PREFIX = "putao:user";

    private static volatile String prefix = DEFAULT_PREFIX;

    public static void setPrefix(String prefix) {
        RedisKey.prefix = (prefix == null || prefix.isBlank()) ? DEFAULT_PREFIX : prefix;
    }

    public static String getPrefix() {
        return prefix;
    }

    // ==================== Profile Cache ====================

    /** 单用户主资料 Hash 缓存(MVP 关键字段,不含 avatar JSON) */
    public static String profile(Long userId) {
        return String.format("%s:profile:%d", prefix, userId);
    }

    /** 单用户大字段缓存(custom_avatar JSONB 等) */
    public static String profileBig(Long userId) {
        return String.format("%s:profile:big:%d", prefix, userId);
    }

    /** 单用户兴趣标签缓存 */
    public static String interest(Long userId) {
        return String.format("%s:interest:%d", prefix, userId);
    }

    /** 批量读取缓存,key 直接是 userId list 拼出来的 hash tag */
    public static String profileBatch(String batchTag) {
        return String.format("%s:profile:batch:%s", prefix, batchTag);
    }

    // ==================== Ban ====================

    /** 用户封禁状态短缓存 */
    public static String banStatus(Long userId) {
        return String.format("%s:ban:status:%d", prefix, userId);
    }

    /** 运营级封禁 Set(由运营管理后台写入) */
    public static String banThirdPartySet() {
        return prefix + ":ban:thirdparty-set";
    }

    // ==================== Register Locks ====================

    /** 手机号注册解析锁 */
    public static String lockRegisterPhone(String phoneE164, String appName) {
        return String.format("%s:lock:register:phone:%s:%s", prefix, phoneE164, appName);
    }

    /** 第三方注册解析锁 */
    public static String lockRegisterThirdParty(int platform, String thirdPartyUserId, String appName) {
        return String.format("%s:lock:register:tp:%d:%s:%s", prefix, platform, thirdPartyUserId, appName);
    }

    /** 设备快速登录注册解析锁 */
    public static String lockRegisterDevice(String deviceId, int platform, String appName) {
        return String.format("%s:lock:register:device:%s:%d:%s", prefix, deviceId, platform, appName);
    }
}
