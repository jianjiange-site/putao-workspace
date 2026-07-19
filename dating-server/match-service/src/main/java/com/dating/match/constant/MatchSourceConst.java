package com.dating.match.constant;

/**
 * match.source 入口动作维度常量.
 *
 * <p>与 BH/DH 正交 — BH/DH 信息由 user_id 反查 user.user_type,不冗余进 source.
 */
public final class MatchSourceConst {

    public static final String SWIPE_MATCH = "SWIPE_MATCH";
    public static final String SWIPE_SUPER_HI = "SWIPE_SUPER_HI";

    private MatchSourceConst() {
    }
}
