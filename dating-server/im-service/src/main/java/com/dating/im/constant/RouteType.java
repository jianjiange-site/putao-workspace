package com.dating.im.constant;

/**
 * 消息路由类型枚举.
 *
 * <p>描述 BH(真人)/DH(数字人)之间的消息流向.
 */
public final class RouteType {

    private RouteType() {}

    /** 真人 -> 真人 */
    public static final String BH_BH = "BH_BH";

    /** 真人 -> 数字人 */
    public static final String BH_DH = "BH_DH";

    /** 数字人 -> 真人 */
    public static final String DH_BH = "DH_BH";

    /** 数字人 -> 数字人 */
    public static final String DH_DH = "DH_DH";

    /** 未知 */
    public static final String UNKNOWN = "UNKNOWN";
}
