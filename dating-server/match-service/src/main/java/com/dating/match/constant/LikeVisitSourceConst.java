package com.dating.match.constant;

/**
 * like_record / visit_record.source 枚举.
 *
 * <p>来源:
 * <ul>
 *   <li>SWIPE_RIGHT(1) — 真人右划产生的单向 like</li>
 *   <li>DH_PLAN_ONLINE(2) — DH 模拟 ONLINE 计划</li>
 *   <li>DH_PLAN_OFFLINE(3) — DH 模拟 OFFLINE 计划</li>
 * </ul>
 */
public final class LikeVisitSourceConst {

    public static final int SWIPE_RIGHT = 1;
    public static final int DH_PLAN_ONLINE = 2;
    public static final int DH_PLAN_OFFLINE = 3;

    /** visit_record 专用 */
    public static final int PROFILE_VIEW = 1;

    private LikeVisitSourceConst() {
    }
}
