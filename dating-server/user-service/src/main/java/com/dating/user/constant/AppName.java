package com.dating.user.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AppName 枚举 — 与 mobile-gateway / user_login_phone.app_name 字段对齐.
 *
 * <p>由于 MyBatis-Plus 不支持枚举与数据库列直接映射(本服务 SMALLINT 列存的是
 * 枚举序号),实体字段用 {@code Integer appName} 接收,这里提供双向转换工具.
 */
@Getter
@AllArgsConstructor
public enum AppName {

    UNSPECIFIED(0, "unspecified"),
    VIBE(1, "vibe");

    private final int code;
    private final String dbValue;

    public static AppName fromCode(Integer code) {
        if (code == null) return UNSPECIFIED;
        for (AppName v : values()) {
            if (v.code == code) return v;
        }
        return UNSPECIFIED;
    }

    public static AppName fromDb(String dbValue) {
        if (dbValue == null) return UNSPECIFIED;
        for (AppName v : values()) {
            if (v.dbValue.equalsIgnoreCase(dbValue)) return v;
        }
        return UNSPECIFIED;
    }

    public static String toDb(Integer code) {
        AppName v = fromCode(code);
        return v.dbValue;
    }
}
