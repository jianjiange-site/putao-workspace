package com.dating.user.config;

import io.grpc.Context;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 业务侧统一入口,获取当前 RPC 调用方的 userId / deviceId / traceId.
 *
 * <p>由 {@link UserIdContextInterceptor} 把 metadata 注入 gRPC Context,
 * 业务代码只需 {@code UserContext.callerUserId()} 即可.
 */
@Slf4j
public final class UserContext {

    private UserContext() {}

    /** 调用方 userId(metadata x-user-id);上游未传则为空 */
    public static Long callerUserId() {
        return UserIdContextInterceptor.USER_ID_CONTEXT_KEY.get();
    }

    public static Optional<Long> callerUserIdOpt() {
        return Optional.ofNullable(callerUserId());
    }

    public static String callerDeviceId() {
        return UserIdContextInterceptor.DEVICE_ID_CONTEXT_KEY.get();
    }

    public static String traceId() {
        return UserIdContextInterceptor.TRACE_ID_CONTEXT_KEY.get();
    }

    /**
     * 强取调用方 userId,缺失抛 IllegalArgumentException.
     *
     * <p>用于 service 层方法体内已经确认上游必传 userId 的场景(例如 UpdateProfile).
     */
    public static Long requireCallerUserId() {
        Long userId = callerUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Missing x-user-id in gRPC metadata");
        }
        return userId;
    }
}
