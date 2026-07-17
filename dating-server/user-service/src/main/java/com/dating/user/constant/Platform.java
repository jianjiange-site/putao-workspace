package com.dating.user.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 设备平台枚举 — 与 mobile-gateway Platform 对齐.
 *
 * <p>user_device_registration.platform 列直接存 code 数值.
 */
@Getter
@AllArgsConstructor
public enum Platform {

    UNKNOWN(0),
    IOS(1),
    ANDROID(2),
    WEB(3);

    private final int code;

    public static Platform fromCode(Integer code) {
        if (code == null) return UNKNOWN;
        for (Platform v : values()) {
            if (v.code == code) return v;
        }
        return UNKNOWN;
    }
}
