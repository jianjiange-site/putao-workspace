package com.dating.user.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 第三方平台枚举 — 与 mobile-gateway ThirdPartyPlatform 对齐.
 *
 * <p>user_third_party_registration.platform 列存 code 数值.
 */
@Getter
@AllArgsConstructor
public enum ThirdPartyPlatform {

    UNKNOWN(0),
    GOOGLE(1),
    FACEBOOK(2),
    APPLE(3);

    private final int code;

    public static ThirdPartyPlatform fromCode(Integer code) {
        if (code == null) return UNKNOWN;
        for (ThirdPartyPlatform v : values()) {
            if (v.code == code) return v;
        }
        return UNKNOWN;
    }
}
