package com.dating.gateway.service.impl;

import com.dating.gateway.client.UserClient;
import com.dating.gateway.config.JwtConfig;
import com.dating.gateway.dto.LoginPhoneReq;
import com.dating.gateway.dto.LoginThirdPartyReq;
import com.dating.gateway.dto.OnboardingReq;
import com.dating.gateway.dto.RefreshTokenReq;
import com.dating.gateway.dto.SendSmsCodeReq;
import com.dating.gateway.entity.AuthDeviceEntity;
import com.dating.gateway.entity.AuthRefreshTokenEntity;
import com.dating.gateway.manager.AuthDeviceManager;
import com.dating.gateway.manager.AuthRefreshTokenManager;
import com.dating.gateway.security.JwtIssuer;
import com.dating.gateway.security.JwtVerifier;
import com.dating.gateway.service.AuthService;
import com.dating.gateway.vo.LoginResultVO;
import com.dating.gateway.vo.UserProfileVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Auth Service Implementation. */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final JwtIssuer jwtIssuer;
    private final JwtVerifier jwtVerifier;
    private final JwtConfig.JwtProperties jwtProperties;
    private final AuthDeviceManager authDeviceManager;
    private final AuthRefreshTokenManager authRefreshTokenManager;
    private final UserClient userClient;

    public AuthServiceImpl(JwtIssuer jwtIssuer, JwtVerifier jwtVerifier,
                          JwtConfig.JwtProperties jwtProperties,
                          AuthDeviceManager authDeviceManager,
                          AuthRefreshTokenManager authRefreshTokenManager,
                          UserClient userClient) {
        this.jwtIssuer = jwtIssuer;
        this.jwtVerifier = jwtVerifier;
        this.jwtProperties = jwtProperties;
        this.authDeviceManager = authDeviceManager;
        this.authRefreshTokenManager = authRefreshTokenManager;
        this.userClient = userClient;
    }

    @Override
    public void sendSmsCode(SendSmsCodeReq req) {
        log.info("Send SMS code to phone: {}", req.getPhone());
    }

    @Override
    public LoginResultVO loginPhone(LoginPhoneReq req) {
        log.info("Login by phone: {}", req.getPhone());
        Long userId = System.currentTimeMillis();
        return createLoginResult(userId, req.getDeviceId(), req.getPlatform(),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken());
    }

    @Override
    public LoginResultVO loginDevice(String deviceId, Integer platform,
            String deviceModel, String osVersion, String appVersion, String pushToken) {
        log.info("Login by device: {}", deviceId);
        var existingDevice = authDeviceManager.findByUserIdAndDeviceId(null, deviceId);
        if (existingDevice.isEmpty()) {
            throw new RuntimeException("Device not found, please use phone login first");
        }
        Long userId = existingDevice.get().getUserId();
        return createLoginResult(userId, deviceId, platform, deviceModel, osVersion, appVersion, pushToken);
    }

    @Override
    public LoginResultVO loginThirdParty(LoginThirdPartyReq req) {
        log.info("Login by third party: {}", req.getThirdPartyPlatform());
        Long userId = System.currentTimeMillis();
        return createLoginResult(userId, req.getDeviceId(), req.getPlatform(),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken());
    }

    @Override
    public LoginResultVO refreshToken(RefreshTokenReq req) {
        log.info("Refresh token");
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void logout(String refreshToken) {
        log.info("Logout");
    }

    @Override
    public void onboarding(Long userId, OnboardingReq req) {
        log.info("Complete onboarding for userId={}", userId);
        userClient.updateUserProfile(userId, req.getNickname(), req.getBio());
    }

    private LoginResultVO createLoginResult(Long userId, String deviceId, Integer platform,
            String deviceModel, String osVersion, String appVersion, String pushToken) {
        authDeviceManager.upsertDevice(userId, deviceId, platform, deviceModel, osVersion, appVersion, pushToken);

        JwtIssuer.TokenPair tokens = jwtIssuer.issueTokens(userId, deviceId);
        String tokenHash = hashToken(tokens.refreshToken());

        AuthRefreshTokenEntity entity = new AuthRefreshTokenEntity();
        entity.setUserId(userId);
        entity.setDeviceId(deviceId);
        entity.setTokenHash(tokenHash);
        entity.setJti(tokens.refreshJti());
        entity.setExpiresAt(jwtIssuer.getRefreshTokenExpiry());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeleted(0);
        authRefreshTokenManager.saveRefreshToken(entity);

        LoginResultVO vo = new LoginResultVO();
        vo.setAccessToken(tokens.accessToken());
        vo.setRefreshToken(tokens.refreshToken());
        vo.setUserId(userId);
        vo.setAccessExpiresAtMs(Instant.now().plusSeconds(jwtProperties.getAccessTokenExpirySeconds()).toEpochMilli());
        vo.setRefreshExpiresAtMs(jwtIssuer.getRefreshTokenExpiry().toEpochMilli());
        return vo;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash token", e);
        }
    }
}
