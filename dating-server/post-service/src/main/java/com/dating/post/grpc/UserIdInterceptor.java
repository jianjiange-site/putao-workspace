package com.dating.post.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * UserId 透传拦截器.
 *
 * <p>从 gRPC Metadata 中提取 mobile-gateway 注入的 x-user-id,
 * 放入 {@link Context} 以便业务代码通过 {@code Context.current()}
 * 在 RPC 方法体内安全获取.
 *
 * <p>设计依据: doc/specs/post-service-design.md
 *  <ul>
 *    <li>gateway 解 JWT 后把 user_id 塞进 metadata(key=x-user-id)</li>
 *    <li>本服务在拦截器层把 metadata 注入 Context,不再依赖过时的 GrpcConstants</li>
 *    <li>业务代码用 {@code Context.current().get(USER_ID_CONTEXT_KEY)} 取值</li>
 *  </ul>
 */
@Slf4j
@Component
@GrpcGlobalServerInterceptor
public class UserIdInterceptor implements ServerInterceptor {

    /** gRPC Metadata 中 userId 头的 key 名 */
    public static final Metadata.Key<String> USER_ID_METADATA_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    /** 业务侧通过 Context.current() 取 userId 的 key */
    public static final Context.Key<Long> USER_ID_CONTEXT_KEY = Context.key("x-user-id");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 1. 从 metadata 解析 userId
        Long userId = parseUserId(headers);

        // 2. 包装 Context,把 userId 注入下游
        Context ctxWithUserId = Context.current().withValue(USER_ID_CONTEXT_KEY, userId);

        // 3. 用 Contexts.interceptCall 让下游监听器在带 userId 的 Context 中执行
        return Contexts.interceptCall(ctxWithUserId, call, headers, next);
    }

    /**
     * 从 metadata 解析 userId,失败返回 null(后续业务层需校验).
     */
    private Long parseUserId(Metadata headers) {
        try {
            String value = headers.get(USER_ID_METADATA_KEY);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid x-user-id in metadata: value={}", headers.get(USER_ID_METADATA_KEY));
            return null;
        }
    }
}