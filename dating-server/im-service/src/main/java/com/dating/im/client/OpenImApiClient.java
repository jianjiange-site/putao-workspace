package com.dating.im.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OpenIM REST API Client.
 *
 * <p>封装 OpenIM 的 HTTP REST API,用于发送消息、注册用户、获取 Token.
 */
@Slf4j
@Component
public class OpenImApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${openim.api-url:http://127.0.0.1:10002}")
    private String apiUrl;

    @Value("${openim.admin-user-id:imAdmin}")
    private String adminUserId;

    @Value("${openim.admin-secret:}")
    private String adminSecret;

    public OpenImApiClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    /**
     * 获取 admin Token.
     */
    public Optional<String> getAdminToken() {
        try {
            String url = apiUrl + "/auth/user_token";

            Map<String, Object> body = new HashMap<>();
            body.put("userID", adminUserId);
            body.put("secret", adminSecret);
            body.put("platform", 1);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                return Optional.of(root.path("token").asText());
            }
        } catch (Exception e) {
            log.error("Failed to get admin token", e);
        }
        return Optional.empty();
    }

    /**
     * 注册用户.
     *
     * @param userId   用户ID
     * @param nickname 昵称
     * @param faceURL  头像
     */
    public boolean registerUser(String userId, String nickname, String faceURL) {
        try {
            String token = getAdminToken().orElse(null);
            if (token == null) {
                log.error("Cannot get admin token for registerUser");
                return false;
            }

            String url = apiUrl + "/user/user_register";

            Map<String, Object> user = new HashMap<>();
            user.put("userID", userId);
            user.put("nickname", nickname);
            user.put("faceURL", faceURL);

            Map<String, Object> body = new HashMap<>();
            body.put("users", new Object[]{user});

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("token", token);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("User registered: userId={}", userId);
                return true;
            }

            String bodyStr = response.getBody();
            if (bodyStr != null && (bodyStr.contains("registered") || bodyStr.contains("exist"))) {
                log.debug("User already exists: userId={}", userId);
                return true;
            }

            log.warn("Failed to register user: userId={}, status={}", userId, response.getStatusCode());
            return false;
        } catch (Exception e) {
            log.error("Failed to register user: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 获取用户 Token.
     *
     * @param userId   用户ID
     * @param nickname 昵称
     * @param faceURL  头像
     */
    public Optional<TokenResult> getUserToken(String userId, String nickname, String faceURL) {
        try {
            String url = apiUrl + "/auth/get_user_token";

            Map<String, Object> body = new HashMap<>();
            body.put("userID", userId);
            body.put("secret", adminSecret);
            body.put("platform", 1);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String token = root.path("token").asText();
                long expireTime = root.path("expireTimeSeconds").asLong();

                log.info("Got user token: userId={}, expireIn={}s", userId, expireTime);
                return Optional.of(new TokenResult(token, expireTime));
            }

            if (response.getStatusCode().value() == 500) {
                // 用户未注册,先注册再重试
                if (registerUser(userId, nickname, faceURL)) {
                    return getUserToken(userId, nickname, faceURL);
                }
            }

            log.warn("Failed to get user token: userId={}, status={}", userId, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to get user token: userId={}", userId, e);
        }
        return Optional.empty();
    }

    /**
     * 发送消息.
     *
     * @param token    Admin token
     * @param senderId 发送者ID
     * @param recvId   接收者ID
     * @param msgType  消息类型
     * @param content  内容
     */
    public Optional<String> sendMsg(String token, String senderId, String recvId, int msgType, String content) {
        try {
            String url = apiUrl + "/msg/send_msg";

            Map<String, Object> msg = new HashMap<>();
            msg.put("senderID", senderId);
            msg.put("recvID", recvId);
            msg.put("msgType", msgType);

            Map<String, Object> textElem = new HashMap<>();
            textElem.put("text", content);
            msg.put("textElem", textElem);

            Map<String, Object> body = new HashMap<>();
            body.put("msg", msg);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("token", token);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String serverMsgId = root.path("data").path("serverMsgID").asText();
                return Optional.of(serverMsgId);
            }

            log.warn("Failed to send message: {} -> {}, status={}", senderId, recvId, response.getStatusCode());
        } catch (Exception e) {
            log.error("Failed to send message: {} -> {}", senderId, recvId, e);
        }
        return Optional.empty();
    }

    /**
     * Token 结果.
     */
    public record TokenResult(String token, long expireSeconds) {}
}
