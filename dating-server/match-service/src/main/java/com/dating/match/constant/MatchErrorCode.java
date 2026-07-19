package com.dating.match.constant;

/**
 * 业务错误码定义.
 *
 * <p>区段划分:
 * <ul>
 *   <li>0           - 成功</li>
 *   <li>3001~3099   - 配额 / 划卡</li>
 *   <li>4001~4099   - 通用参数 / 业务校验</li>
 *   <li>5001~5099   - Match / Like / Visit</li>
 *   <li>9999        - 未实现占位</li>
 * </ul>
 */
public final class MatchErrorCode {

    /** 成功 */
    public static final int OK = 0;

    /** 右划配额超限 */
    public static final int QUOTA_RIGHT_SWIPE_EXCEEDED = 3001;
    /** 卡片配额超限(可划卡片整体上限) */
    public static final int QUOTA_CARDS_EXCEEDED = 3002;
    /** Super Hi 配额(订阅赠送)超限且金币不足 */
    public static final int SUPER_HI_INSUFFICIENT = 3003;
    /** 划卡目标已注销 / 不存在 */
    public static final int TARGET_USER_NOT_FOUND = 3004;
    /** 自访问短路 */
    public static final int SELF_OPERATION = 3005;
    /** 并发 swipe 同一 target 失败(锁竞争) */
    public static final int CONCURRENT_SWIPE = 3006;
    /** 同性 swipe(异性恋假设) */
    public static final int SAME_GENDER_SWIPE = 3007;

    /** 缺少必填参数 */
    public static final int MISSING_PARAMETER = 4001;
    /** 用户不存在 */
    public static final int USER_NOT_FOUND = 4002;
    /** 列表分页越界 */
    public static final int PAGE_INVALID = 4003;

    /** match 创建失败(DB 约束,正常不应发生) */
    public static final int MATCH_CREATE_FAILED = 5001;
    /** 重复 match 触发 */
    public static final int DUPLICATE_MATCH = 5002;
    /** 召回为空(无可用候选) */
    public static final int EMPTY_CANDIDATES = 5003;

    /** 占位:通道或接口未实现 */
    public static final int NOT_IMPLEMENTED = 9999;

    private MatchErrorCode() {
    }

    public static String getMessage(int code) {
        return switch (code) {
            case OK -> "OK";
            case QUOTA_RIGHT_SWIPE_EXCEEDED -> "Daily right-swipe quota exceeded";
            case QUOTA_CARDS_EXCEEDED -> "Daily cards quota exceeded";
            case SUPER_HI_INSUFFICIENT -> "Super Hi quota and coins insufficient";
            case TARGET_USER_NOT_FOUND -> "Target user not found";
            case SELF_OPERATION -> "Self operation not allowed";
            case CONCURRENT_SWIPE -> "Concurrent swipe on the same target";
            case SAME_GENDER_SWIPE -> "Same gender swipe rejected";
            case MISSING_PARAMETER -> "Missing required parameter";
            case USER_NOT_FOUND -> "User not found";
            case PAGE_INVALID -> "Invalid page token";
            case MATCH_CREATE_FAILED -> "Match create failed";
            case DUPLICATE_MATCH -> "Duplicate match (already matched)";
            case EMPTY_CANDIDATES -> "No candidates available";
            case NOT_IMPLEMENTED -> "NOT_IMPLEMENTED";
            default -> "Unknown error code: " + code;
        };
    }
}
