package com.dating.post.constant;

/**
 * Redis Key 常量.
 *
 * <p>Key格式: <prefix>:post:* / <prefix>:user:timeline:* / <prefix>:feed:* / <prefix>:lock:post:*
 * 前缀通过 setPrefix() 方法从配置注入.
 */
public final class RedisKey {

    private RedisKey() {}

    /** 默认key前缀 */
    public static final String DEFAULT_PREFIX = "putao";

    private static String prefix = DEFAULT_PREFIX;

    /**
     * 设置key前缀(从配置注入).
     */
    public static void setPrefix(String prefix) {
        RedisKey.prefix = prefix != null ? prefix : DEFAULT_PREFIX;
    }

    public static String getPrefix() {
        return prefix;
    }

    // ==================== Post Keys ====================

    /** 帖子详情缓存 */
    public static String postDetail(Long postId) {
        return String.format("%s:post:detail:%d", prefix, postId);
    }

    /** 点赞未刷盘增量 */
    public static String likeIncr(Long postId) {
        return String.format("%s:post:stat:incr:%d:likes", prefix, postId);
    }

    /** 评论未刷盘增量 */
    public static String commentIncr(Long postId) {
        return String.format("%s:post:stat:incr:%d:comments", prefix, postId);
    }

    /** 帖子评论ZSet(最新200条) */
    public static String commentsZSet(Long postId) {
        return String.format("%s:post:comments:%d", prefix, postId);
    }

    /** 待刷盘的post_id集合 */
    public static String updatedSet() {
        return prefix + ":post:updated_set";
    }

    // ==================== User Timeline Keys ====================

    /** 用户好友时间线 */
    public static String userTimeline(Long userId) {
        return String.format("%s:user:timeline:%d", prefix, userId);
    }

    // ==================== Feed Pool Keys ====================

    /** 全网热门池(男性看到的女性帖子) */
    public static String feedPoolRecommendMale() {
        return prefix + ":feed:pool:recommend:male";
    }

    /** 全网热门池(女性看到的男性帖子) */
    public static String feedPoolRecommendFemale() {
        return prefix + ":feed:pool:recommend:female";
    }

    /** 热门池影子key(重建时使用) */
    public static String feedPoolRecommendMaleTmp() {
        return prefix + ":feed:pool:recommend:male:tmp";
    }

    /** 热门池影子key(重建时使用) */
    public static String feedPoolRecommendFemaleTmp() {
        return prefix + ":feed:pool:recommend:female:tmp";
    }

    /** 冷启动池(男性) */
    public static String coldStartPoolMale() {
        return prefix + ":feed:cold_start:pool:male";
    }

    /** 冷启动池(女性) */
    public static String coldStartPoolFemale() {
        return prefix + ":feed:cold_start:pool:female";
    }

    /** 用户已读布隆过滤器 */
    public static String userReadBloom(Long userId) {
        return String.format("%s:user:read:bloom:%d", prefix, userId);
    }

    // ==================== Lock Keys ====================

    /** 帖子操作分布式锁 */
    public static String postLock(Long postId) {
        return String.format("%s:lock:post:%d", prefix, postId);
    }
}
