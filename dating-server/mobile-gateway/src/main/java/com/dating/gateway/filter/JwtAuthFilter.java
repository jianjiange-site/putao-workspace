package com.dating.gateway.filter;

import com.dating.gateway.security.JwtVerifier;
import com.dating.gateway.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JWT Authentication Filter.
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TRACE_ID_HEADER = "X-Trace-ID";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/sms/send",
            "/api/v1/auth/refresh",
            "/api/v1/auth/login/phone",
            "/api/v1/auth/login/device",
            "/api/v1/auth/login/third-party",
            "/api/v1/health",
            "/swagger-ui",
            "/v3/api-docs",
            "/actuator"
    );

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtVerifier jwtVerifier, ObjectMapper objectMapper) {
        this.jwtVerifier = jwtVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (traceId == null || traceId.isEmpty()) {
                traceId = UUID.randomUUID().toString();
            }

            String path = request.getRequestURI();
            if (isPublicPath(path)) {
                RequestContext.set(null, null, traceId, null);
                filterChain.doFilter(request, response);
                return;
            }

            String authHeader = request.getHeader(AUTHORIZATION_HEADER);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                sendUnauthorizedResponse(response, 401, "Missing or invalid Authorization header");
                return;
            }

            String token = authHeader.substring(BEARER_PREFIX.length());
            var claimsOpt = jwtVerifier.verifyAccessToken(token);
            if (claimsOpt.isEmpty()) {
                sendUnauthorizedResponse(response, 401, "Invalid or expired token");
                return;
            }

            var claims = claimsOpt.get();
            RequestContext.set(claims.userId(), claims.deviceId(), traceId, claims.jti());
            response.setHeader(TRACE_ID_HEADER, traceId);

            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> errorResponse = Map.of("code", code, "message", message, "data", (Object) null);
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
