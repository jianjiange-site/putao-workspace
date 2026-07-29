package com.dating.post.constant;

/**
 * Redis Key 常量.
 */
public final class RedisKey {

    private RedisKey() {}

    public static final String DEFAULT_PREFIX = "putao";
    private static String prefix = DEFAULT_PREFIX;

    /**
     * 兼容旧配置 putao:post；内部统一保存根前缀 putao，避免 post:post.
     */
    public static void setPrefix(String configuredPrefix) {
        String normalized = configuredPrefix == null || configuredPrefix.isBlank()
                ? DEFAULT_PREFIX
                : configuredPrefix.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(":post")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        prefix = normalized.isBlank() ? DEFAULT_PREFIX : normalized;
    }

    public static String getPrefix() {
        return prefix;
    }

    public static String postDetail(Long postId) {
        return String.format("%s:post:detail:v1:%d", prefix, postId);
    }

    public static String postDetailLoadLock(Long postId) {
        return String.format("%s:lock:post:detail:%d", prefix, postId);
    }

    public static String postDetailEvictChannel() {
        return prefix + ":post:detail:evict";
    }

    public static String likeIncr(Long postId) {
        return String.format("%s:post:stat:incr:%d:likes", prefix, postId);
    }

    public static String commentIncr(Long postId) {
        return String.format("%s:post:stat:incr:%d:comments", prefix, postId);
    }

    @Deprecated
    public static String updatedSet() {
        return prefix + ":post:updated_set";
    }

    public static String likeUpdatedSet() {
        return prefix + ":post:like:updated_set";
    }

    public static String commentsZSet(Long postId) {
        return String.format("%s:post:comments:%d", prefix, postId);
    }

    public static String userTimeline(Long userId) {
        return String.format("%s:user:timeline:%d", prefix, userId);
    }

    public static String feedPoolRecommendMale() {
        return prefix + ":feed:pool:recommend:male";
    }

    public static String feedPoolRecommendFemale() {
        return prefix + ":feed:pool:recommend:female";
    }

    public static String feedPoolRecommendMaleTmp() {
        return prefix + ":feed:pool:recommend:male:tmp";
    }

    public static String feedPoolRecommendFemaleTmp() {
        return prefix + ":feed:pool:recommend:female:tmp";
    }

    public static String coldStartPoolMale() {
        return prefix + ":feed:cold_start:pool:male";
    }

    public static String coldStartPoolFemale() {
        return prefix + ":feed:cold_start:pool:female";
    }

    public static String userReadBloom(Long userId) {
        return String.format("%s:user:read:bloom:%d", prefix, userId);
    }

    public static String userGender(Long userId) {
        return String.format("%s:post:user:gender:%d", prefix, userId);
    }

    public static String postLock(Long postId) {
        return String.format("%s:lock:post:%d", prefix, postId);
    }
}
