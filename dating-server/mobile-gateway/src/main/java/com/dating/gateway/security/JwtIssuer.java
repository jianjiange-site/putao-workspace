package com.dating.gateway.security;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** JWT Token Issuer. */
@Slf4j
@Component
public class JwtIssuer {

    private static final String TYPE_CLAIM = "type";
    private static final String DEVICE_ID_CLAIM = "device_id";
    private static final String ACCESS_TYPE = "access";
    private static final String REFRESH_TYPE = "refresh";

    private final KeyPair keyPair;
    private final JwtConfig.JwtProperties jwtProperties;

    public JwtIssuer(KeyPair keyPair, JwtConfig.JwtProperties jwtProperties) {
        this.keyPair = keyPair;
        this.jwtProperties = jwtProperties;
    }

    public record TokenPair(String accessToken, String refreshToken, String accessJti, String refreshJti) {}

    public TokenPair issueTokens(Long userId, String deviceId) {
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();

        Map<String, Object> accessClaims = new HashMap<>();
        accessClaims.put(TYPE_CLAIM, ACCESS_TYPE);
        accessClaims.put(DEVICE_ID_CLAIM, deviceId);

        Instant now = Instant.now();
        Instant accessExpiry = now.plus(jwtProperties.getAccessTokenExpirySeconds(), ChronoUnit.SECONDS);

        String accessToken = Jwts.builder()
                .subject(userId.toString())
                .id(accessJti)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExpiry))
                .claims(accessClaims)
                .signWith(keyPair.getPrivate())
                .compact();

        Map<String, Object> refreshClaims = new HashMap<>();
        refreshClaims.put(TYPE_CLAIM, REFRESH_TYPE);
        refreshClaims.put(DEVICE_ID_CLAIM, deviceId);
        refreshClaims.put("access_jti", accessJti);

        Instant refreshExpiry = now.plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);

        String refreshToken = Jwts.builder()
                .subject(userId.toString())
                .id(refreshJti)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(refreshExpiry))
                .claims(refreshClaims)
                .signWith(keyPair.getPrivate())
                .compact();

        log.info("Issued tokens for userId={}, deviceId={}", userId, deviceId);
        return new TokenPair(accessToken, refreshToken, accessJti, refreshJti);
    }

    public Instant getRefreshTokenExpiry() {
        return Instant.now().plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);
    }
}
