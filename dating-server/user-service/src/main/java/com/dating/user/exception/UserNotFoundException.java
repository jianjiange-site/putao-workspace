package com.dating.user.exception;

import com.dating.user.constant.ErrorCode;

/**
 * 用户不存在异常 — 在调用方传了无效 userId 时抛出.
 */
public class UserNotFoundException extends BizException {

    public UserNotFoundException(Long userId) {
        super(ErrorCode.USER_NOT_FOUND, "User not found: " + userId);
    }

    public UserNotFoundException(String reason) {
        super(ErrorCode.USER_NOT_FOUND, reason);
    }
}
