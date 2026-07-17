package com.dating.im.service;

import com.dating.im.constant.NotificationKeys;
import com.dating.im.client.OpenImApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 通知服务.
 *
 * <p>封装 OpenIM 业务通知下发.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final OpenImApiClient openImApiClient;
    private final ObjectMapper objectMapper;

    @Value("${openim.admin-token:}")
    private String adminToken;

    /**
     * 下发 typing 信号.
     *
     * @param senderId   发送者ID
     * @param receiverId 接收者ID
     */
    public void sendTyping(String senderId, String receiverId) {
        sendBusinessNotification(
                senderId,
                receiverId,
                NotificationKeys.TYPING,
                Map.of("typing", true)
        );
    }

    /**
     * 下发停止 typing 信号.
     *
     * @param senderId   发送者ID
     * @param receiverId 接收者ID
     */
    public void sendStopTyping(String senderId, String receiverId) {
        sendBusinessNotification(
                senderId,
                receiverId,
                NotificationKeys.TYPING,
                Map.of("typing", false)
        );
    }

    /**
     * 下发业务通知.
     *
     * @param senderId    发送者ID
     * @param receiverId  接收者ID
     * @param notificationKey 通知类型
     * @param data        通知数据
     */
    public void sendBusinessNotification(String senderId, String receiverId,
                                        String notificationKey, Map<String, Object> data) {
        try {
            String token = getAdminToken();
            if (token == null) {
                log.error("Cannot send notification: no admin token");
                return;
            }

            String jsonData = objectMapper.writeValueAsString(data);

            String serverMsgId = openImApiClient.sendMsg(
                    token,
                    senderId,
                    receiverId,
                    100, // 自定义消息类型
                    jsonData
            ).orElse(null);

            if (serverMsgId != null) {
                log.debug("Notification sent: key={}, {} -> {}", notificationKey, senderId, receiverId);
            }
        } catch (Exception e) {
            log.error("Failed to send notification: key={}, {} -> {}", notificationKey, senderId, receiverId, e);
        }
    }

    private String getAdminToken() {
        if (adminToken != null && !adminToken.isEmpty()) {
            return adminToken;
        }
        return openImApiClient.getAdminToken().orElse(null);
    }
}
