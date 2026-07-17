package com.dating.user.exception;

import com.dating.user.constant.ErrorCode;

/**
 * 用户被封禁异常 — 由 CheckBan 或 ResolveOrCreate 时发现 regulation_status ∈ {2,5} 抛出.
 */
public class UserBannedException extends BizException {

    public UserBannedException(Long userId, ErrorCode code) {
        super(code, "User " + userId + " is banned/suspended");
    }

    public UserBannedException(ErrorCode code, String reason) {
        super(code, reason);
    }
}
