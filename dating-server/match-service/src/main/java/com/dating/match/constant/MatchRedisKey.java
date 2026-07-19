package com.dating.match.constant;

/**
 * Redis Key 统一前缀规范.
 *
 * <pre>putao:match:&lt;domain&gt;:&lt;id&gt;</pre>
 */
public final class MatchRedisKey {

    /** 前缀 */
    public static final String PREFIX = "putao:match";

    /** 当日配额 HASH — field: right_swipe / cards / super_hi */
    public static final String QUOTA = PREFIX + ":quota:%s:%s";
    /** D0/D1 队列 LIST */
    public static final String FEED = PREFIX + ":feed:%s";
    /** 用户已 swipe SET */
    public static final String SWIPED = PREFIX + ":swiped:%s";
    /** 用户偏好画像 HASH cache */
    public static final String PREF = PREFIX + ":pref:%s";

    /** D1 cron Redisson 锁 */
    public static final String LOCK_D1 = "lock:match:d1:%s";
    /** swipe 串行化锁 */
    public static final String LOCK_SWIPE = "lock:match:swipe:%s:%s";
    /** match 创建串行化锁(防重复 match) */
    public static final String LOCK_MATCH = "lock:match:pair:%s:%s";
    /** DH 计划 ONLINE sweep 锁 */
    public static final String LOCK_DH_PLAN_ONLINE = "lock:match:dh_plan:online_sweep";
    /** DH 计划 OFFLINE sweep 锁 */
    public static final String LOCK_DH_PLAN_OFFLINE = "lock:match:dh_plan:offline_sweep";
    /** DH 计划 executor 锁 */
    public static final String LOCK_DH_PLAN_EXECUTOR = "lock:match:dh_plan:executor";

    /** DH 计划 ONLINE 游标 */
    public static final String DH_PLAN_CURSOR_ONLINE = PREFIX + ":dh_plan:cursor:online";
    /** DH 计划 OFFLINE 游标 */
    public static final String DH_PLAN_CURSOR_OFFLINE = PREFIX + ":dh_plan:cursor:offline";
    /** DH 计划 cooldown (per user) */
    public static final String DH_PLAN_COOLDOWN = PREFIX + ":dh_plan:cooldown:%s";
    /** DH 计划 lastScene (per user) */
    public static final String DH_PLAN_LAST_SCENE = PREFIX + ":dh_plan:last_scene:%s";

    private MatchRedisKey() {
    }

    public static String quota(Long userId, String yyyymmdd) {
        return String.format(QUOTA, userId, yyyymmdd);
    }

    public static String feed(Long userId) {
        return String.format(FEED, userId);
    }

    public static String swiped(Long userId) {
        return String.format(SWIPED, userId);
    }

    public static String pref(Long userId) {
        return String.format(PREF, userId);
    }

    public static String lockD1(String yyyymmdd) {
        return String.format(LOCK_D1, yyyymmdd);
    }

    public static String lockSwipe(Long userId, Long targetId) {
        return String.format(LOCK_SWIPE, userId, targetId);
    }

    public static String lockMatch(long low, long high) {
        return String.format(LOCK_MATCH, low, high);
    }

    public static String dhPlanCooldown(Long userId) {
        return String.format(DH_PLAN_COOLDOWN, userId);
    }

    public static String dhPlanLastScene(Long userId) {
        return String.format(DH_PLAN_LAST_SCENE, userId);
    }
}
