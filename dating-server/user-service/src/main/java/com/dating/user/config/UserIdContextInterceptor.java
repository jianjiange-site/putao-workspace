package com.dating.user.config;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * gRPC ServerInterceptor — 从 Metadata 中提取 x-user-id / x-device-id / x-trace-id,
 * 注入 gRPC Context,供业务代码通过 {@code Context.current().get(...)} 取值.
 *
 * <p>设计依据: doc/specs/user-service-design.md §5.1
 * <ul>
 *   <li>gateway 解 JWT 后把 user_id 塞进 metadata(key=x-user-id)</li>
 *   <li>本服务拦截器层把 metadata 注入 Context,供 service 层通过
 *       {@code UserContext.callerUserId()} 取值</li>
 *   <li>trace 缺失时生成 UUID,日志 pattern 用 traceId</li>
 * </ul>
 *
 * <p>注意: 不在拦截器里写 MDC,因为 gRPC RPC 默认是虚拟线程模型(grpc-spring-boot-starter
 * 把每条 RPC 调度到独立 executor),MDC 会丢上下文;日志 traceId 由 logger 自带的 MDC
 * 上游(SLF4J MDC context capture)补,本服务主要靠 gRPC Context 跨层传递.
 */
@Slf4j
@Component
@GrpcGlobalServerInterceptor
public class UserIdContextInterceptor implements ServerInterceptor {

    /** gRPC Metadata 中 userId 头的 key */
    public static final Metadata.Key<String> USER_ID_METADATA_KEY =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    /** gRPC Metadata 中 deviceId 头的 key */
    public static final Metadata.Key<String> DEVICE_ID_METADATA_KEY =
            Metadata.Key.of("x-device-id", Metadata.ASCII_STRING_MARSHALLER);

    /** gRPC Metadata 中 traceId 头的 key */
    public static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    /** 业务侧通过 Context.current() 取 userId 的 key */
    public static final Context.Key<Long> USER_ID_CONTEXT_KEY = Context.key("x-user-id");

    /** 业务侧通过 Context.current() 取 deviceId 的 key */
    public static final Context.Key<String> DEVICE_ID_CONTEXT_KEY = Context.key("x-device-id");

    /** 业务侧通过 Context.current() 取 traceId 的 key */
    public static final Context.Key<String> TRACE_ID_CONTEXT_KEY = Context.key("x-trace-id");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        // 1. 解析 metadata
        Long userId = parseLong(headers.get(USER_ID_METADATA_KEY));
        String deviceId = headers.get(DEVICE_ID_METADATA_KEY);
        String traceId = headers.get(TRACE_ID_METADATA_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        // 2. 注入 gRPC Context,下游在新的 Context 中执行
        Context ctx = Context.current()
                .withValue(USER_ID_CONTEXT_KEY, userId)
                .withValue(DEVICE_ID_CONTEXT_KEY, deviceId)
                .withValue(TRACE_ID_CONTEXT_KEY, traceId);

        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("invalid x-user-id metadata: {}", value);
            return null;
        }
    }
}
