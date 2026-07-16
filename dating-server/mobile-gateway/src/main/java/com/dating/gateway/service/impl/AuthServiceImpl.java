package com.dating.gateway.service.impl;

import com.dating.gateway.client.UserClient;
import com.dating.gateway.config.JwtConfig;
import com.dating.gateway.dto.*;
import com.dating.gateway.entity.AuthDeviceEntity;
import com.dating.gateway.manager.AuthDeviceManager;
import com.dating.gateway.manager.AuthRefreshTokenManager;
import com.dating.gateway.security.JwtIssuer;
import com.dating.gateway.security.JwtVerifier;
import com.dating.gateway.service.AuthService;
import com.dating.gateway.vo.LoginResultVO;
import com.dating.gateway.vo.UserProfileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Auth Service Implementation. */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

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
    public void sendSmsCode(String phone) {
        log.info("Send SMS code to phone: {}", phone);
    }

    @Override
    public LoginResultVO loginByPhone(LoginPhoneReq req) {
        log.info("Login by phone: {}", req.getPhone());
        Long userId = System.currentTimeMillis();
        return createLoginResult(userId, req.getDeviceId(), req.getPlatform(),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken());
    }

    @Override
    public LoginResultVO loginByDevice(LoginDeviceReq req) {
        log.info("Login by device: {}", req.getDeviceId());
        Optional<AuthDeviceEntity> existingDevice = authDeviceManager.findByUserIdAndDeviceId(null, req.getDeviceId());
        if (existingDevice.isEmpty()) {
            throw new RuntimeException("Device not found, please use phone login first");
        }
        Long userId = existingDevice.get().getUserId();
        return createLoginResult(userId, req.getDeviceId(), req.getPlatform(),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken());
    }

    @Override
    public LoginResultVO loginByThirdParty(LoginThirdPartyReq req) {
        log.info("Login by third party: {}", req.getThirdPartyPlatform());
        Long userId = System.currentTimeMillis();
        return createLoginResult(userId, req.getDeviceId(), req.getPlatform(),
                req.getDeviceModel(), req.getOsVersion(), req.getAppVersion(), req.getPushToken());
    }

    @Override
    public LoginResultVO refreshToken(String refreshToken) {
        log.info("Refresh token");
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void logout(Long userId, String deviceId, String accessJti, long ttlSeconds) {
        log.info("Logout userId={}, deviceId={}", userId, deviceId);
        jwtVerifier.blacklist(accessJti, ttlSeconds);
    }

    @Override
    public UserProfileVO completeOnboarding(Long userId, OnboardingReq req) {
        log.info("Complete onboarding for userId={}", userId);
        var profile = userClient.updateUserProfile(userId, req.getNickname(), req.getDefaultAvatarObjectKey(), req.getBio());
        return UserProfileVO.builder()
                .userId(userId)
                .nickname(profile.getNickname())
                .age(profile.getAge())
                .bio(profile.getBio())
                .avatar(profile.getAvatarKey())
                .build();
    }

    private LoginResultVO createLoginResult(Long userId, String deviceId, Integer platform,
            String deviceModel, String osVersion, String appVersion, String pushToken) {
        authDeviceManager.upsertDevice(userId, deviceId, platform, deviceModel, osVersion, appVersion, pushToken);
        
        JwtIssuer.TokenPair tokens = jwtIssuer.issueTokens(userId, deviceId);
        String tokenHash = hashToken(tokens.refreshToken());
        authRefreshTokenManager.createRefreshToken(userId, deviceId, tokenHash, tokens.refreshJti(),
                jwtIssuer.getRefreshTokenExpiry());

        return LoginResultVO.builder()
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .expiresIn(jwtProperties.getAccessTokenExpirySeconds())
                .userId(userId)
                .build();
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
