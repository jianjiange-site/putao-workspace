package com.dating.gateway.security;

/**
 * Request Context for holding authenticated user information.
 * Uses ThreadLocal to store request-scoped data.
 */
public class RequestContext {

    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

    private final Long userId;
    private final String deviceId;
    private final String traceId;
    private final String accessJti;

    private RequestContext(Long userId, String deviceId, String traceId, String accessJti) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.traceId = traceId;
        this.accessJti = accessJti;
    }

    public static void set(Long userId, String deviceId, String traceId, String accessJti) {
        CONTEXT.set(new RequestContext(userId, deviceId, traceId, accessJti));
    }

    public static RequestContext current() {
        RequestContext ctx = CONTEXT.get();
        return ctx != null ? ctx : new RequestContext(null, null, null, null);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public Long getUserId() { return userId; }
    public String getDeviceId() { return deviceId; }
    public String getTraceId() { return traceId; }
    public String getAccessJti() { return accessJti; }
}
