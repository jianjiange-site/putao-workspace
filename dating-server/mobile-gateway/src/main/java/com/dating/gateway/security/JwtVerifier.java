package com.dating.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Optional;

/**
 * JWT Token Verifier.
 */
@Component
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);
    private static final String BLACKLIST_PREFIX = "gateway:auth:blacklist:";
    private static final String TYPE_CLAIM = "type";
    private static final String ACCESS_TYPE = "access";

    private final KeyPair keyPair;
    private final RedisTemplate<String, Object> redisTemplate;

    public JwtVerifier(KeyPair keyPair, RedisTemplate<String, Object> redisTemplate) {
        this.keyPair = keyPair;
        this.redisTemplate = redisTemplate;
    }

    public record JwtClaims(Long userId, String deviceId, String jti, Claims rawClaims) {}

    public Optional<JwtClaims> verifyAccessToken(String token) {
        try {
            Claims claims = parseToken(token);
            
            String type = claims.get(TYPE_CLAIM, String.class);
            if (!ACCESS_TYPE.equals(type)) {
                log.debug("Token type mismatch");
                return Optional.empty();
            }

            String jti = claims.getId();
            if (isBlacklisted(jti)) {
                log.debug("Token is blacklisted");
                return Optional.empty();
            }

            Long userId = Long.parseLong(claims.getSubject());
            String deviceId = claims.get("device_id", String.class);

            return Optional.of(new JwtClaims(userId, deviceId, jti, claims));
        } catch (Exception e) {
            log.debug("Token verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    public void blacklist(String jti, long ttlSeconds) {
        String key = BLACKLIST_PREFIX + jti;
        redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
        log.info("Token blacklisted, jti={}, ttl={}s", jti, ttlSeconds);
    }
}
