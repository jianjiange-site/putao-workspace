package com.dating.im.adaptor;

import com.dating.im.model.ImEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenIM 回调适配器.
 *
 * <p>将 OpenIM 原始 JSON 回调解析成内部归一化事件.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenImAdaptor {

    private final ObjectMapper objectMapper;

    /** OpenIM 回调类型 */
    private static final String CALLBACK_BEFORE_SEND_MSG = "callbackBeforeSendMsg";
    private static final String CALLBACK_AFTER_SEND_MSG = "callbackAfterSendMsg";
    private static final String CALLBACK_USER_ONLINE = "callbackUserOnlineCommand";
    private static final String CALLBACK_USER_OFFLINE = "callbackUserOfflineCommand";

    /**
     * 判断是否支持该 provider.
     */
    public boolean supports(String provider) {
        return "openim".equalsIgnoreCase(provider);
    }

    /**
     * 解析原始回调为 IM 事件.
     */
    public ImEvent parse(byte[] rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);

            String eventType = root.path("callbackType").asText("unknown");

            return switch (eventType) {
                case CALLBACK_BEFORE_SEND_MSG -> parseBeforeSendMsg(root);
                case CALLBACK_AFTER_SEND_MSG -> parseAfterSendMsg(root);
                case CALLBACK_USER_ONLINE -> parseUserOnline(root);
                case CALLBACK_USER_OFFLINE -> parseUserOffline(root);
                default -> {
                    log.warn("Unknown OpenIM callback type: {}", eventType);
                    yield ImEvent.UnknownEvent.builder()
                            .type(eventType)
                            .provider("openim")
                            .build();
                }
            };
        } catch (Exception e) {
            log.error("Failed to parse OpenIM callback", e);
            return ImEvent.UnknownEvent.builder()
                    .type("parse_error")
                    .provider("openim")
                    .build();
        }
    }

    private ImEvent.MessageBeforeSendEvent parseBeforeSendMsg(JsonNode root) {
        JsonNode msgData = root.path("msgData");

        return ImEvent.MessageBeforeSendEvent.builder()
                .messageId(msgData.path("serverMsgID").asText(""))
                .fromUserId(parseUserId(msgData.path("sendID")))
                .toUserId(parseUserId(msgData.path("recvID")))
                .content(msgData.path("textElem").path("text").asText(""))
                .msgType(msgData.path("msgType").asInt(1))
                .conversationType(msgData.path("sessionType").asText("SINGLE"))
                .timestamp(msgData.path("sendTime").asLong(0) / 1000) // OpenIM 毫秒转秒
                .build();
    }

    private ImEvent.MessageSentEvent parseAfterSendMsg(JsonNode root) {
        JsonNode msgData = root.path("msgData");

        return ImEvent.MessageSentEvent.builder()
                .messageId(msgData.path("serverMsgID").asText(""))
                .fromUserId(parseUserId(msgData.path("sendID")))
                .toUserId(parseUserId(msgData.path("recvID")))
                .content(msgData.path("textElem").path("text").asText(""))
                .msgType(msgData.path("msgType").asInt(1))
                .conversationType(msgData.path("sessionType").asText("SINGLE"))
                .timestamp(msgData.path("sendTime").asLong(0) / 1000)
                .provider("openim")
                .build();
    }

    private ImEvent.UserOnlineEvent parseUserOnline(JsonNode root) {
        return ImEvent.UserOnlineEvent.builder()
                .userId(parseUserId(root.path("userID")))
                .platform(root.path("platformID").asInt(1))
                .onlineAt(root.path("onlineTime").asLong(0))
                .build();
    }

    private ImEvent.UserOfflineEvent parseUserOffline(JsonNode root) {
        return ImEvent.UserOfflineEvent.builder()
                .userId(parseUserId(root.path("userID")))
                .platform(root.path("platformID").asInt(1))
                .offlineAt(root.path("offlineTime").asLong(0))
                .build();
    }

    private Long parseUserId(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        try {
            String idStr = node.asText();
            if (idStr == null || idStr.isEmpty()) {
                return null;
            }
            // OpenIM userID 可能是字符串数字
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid user ID format: {}", node.asText());
            return null;
        }
    }
}
