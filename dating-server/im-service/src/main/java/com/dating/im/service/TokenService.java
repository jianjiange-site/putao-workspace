package com.dating.im.service;

import com.dating.im.client.OpenImApiClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Token 服务.
 *
 * <p>签发 OpenIM Token 和 LiveKit 通话 Token.
 */
@Slf4j
@Service
public class TokenService {

    private final OpenImApiClient openImApiClient;

    @Value("${livekit.api-key:}")
    private String livekitApiKey;

    @Value("${livekit.secret-key:}")
    private String livekitSecretKey;

    public TokenService(OpenImApiClient openImApiClient) {
        this.openImApiClient = openImApiClient;
    }

    /**
     * 获取 OpenIM Token.
     *
     * @param userId   用户ID
     * @param nickname 昵称
     * @param avatarKey 头像 Key
     * @return Token 结果
     */
    public ImTokenResult getImToken(Long userId, String nickname, String avatarKey) {
        String userIdStr = userId.toString();
        String avatarUrl = buildAvatarUrl(avatarKey);

        Optional<OpenImApiClient.TokenResult> result = openImApiClient.getUserToken(userIdStr, nickname, avatarUrl);

        if (result.isPresent()) {
            OpenImApiClient.TokenResult tokenResult = result.get();
            return new ImTokenResult(tokenResult.token(), tokenResult.expireSeconds());
        }

        return new ImTokenResult(null, 0);
    }

    /**
     * 生成 LiveKit 通话 Token.
     *
     * @param userId  用户ID
     * @param peerId  对方用户ID(字符串)
     * @return LiveKit Token
     */
    public String generateCallToken(Long userId, String peerId) {
        if (livekitApiKey == null || livekitSecretKey == null || livekitApiKey.isEmpty()) {
            log.warn("LiveKit not configured, returning empty token");
            return "";
        }

        try {
            String roomName = "call_" + userId + "_" + peerId;
            long now = System.currentTimeMillis() / 1000;
            long ttl = 30 * 60; // 30 minutes

            SecretKey key = Keys.hmacShaKeyFor(livekitSecretKey.getBytes(StandardCharsets.UTF_8));

            Map<String, Object> grants = new HashMap<>();
            grants.put("roomJoin", true);
            grants.put("canPublish", true);
            grants.put("canSubscribe", true);

            Map<String, Object> video = new HashMap<>();
            video.put("roomAdmin", true);
            video.put("roomCreate", true);
            video.put("roomJoin", true);
            video.put("canPublish", true);
            video.put("canSubscribe", true);
            grants.put("video", video);

            return Jwts.builder()
                    .issuer(livekitApiKey)
                    .subject(userId.toString())
                    .claim("room", roomName)
                    .claim("grants", grants)
                    .issuedAt(new Date(now * 1000))
                    .expiration(new Date((now + ttl) * 1000))
                    .signWith(key)
                    .compact();
        } catch (Exception e) {
            log.error("Failed to generate LiveKit token: userId={}", userId, e);
            return "";
        }
    }

    /**
     * 懒注册用户到 OpenIM.
     */
    public void registerUser(Long userId, String nickname, String avatarKey) {
        String userIdStr = userId.toString();
        String avatarUrl = buildAvatarUrl(avatarKey);
        openImApiClient.registerUser(userIdStr, nickname, avatarUrl);
    }

    private String buildAvatarUrl(String avatarKey) {
        if (avatarKey == null || avatarKey.isEmpty()) {
            return "";
        }
        // 假设 MinIO API URL,实际从配置读取
        return "https://minio-api.jianjiange.site/" + avatarKey;
    }

    /**
     * OpenIM Token 结果.
     */
    public record ImTokenResult(String token, long expireSeconds) {}
}
