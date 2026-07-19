package com.dating.match.constant;

/**
 * 划卡方向常量.
 *
 * <p>对齐 user_swipe_history.direction. SUPER_HI 走独立 RPC,不入库 direction.
 */
public final class SwipeDirectionConst {

    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int SUPER_HI = 3;

    private SwipeDirectionConst() {
    }
}
