package com.dating.im.service;

import com.dating.im.client.UserServiceClient;
import com.dating.im.constant.RouteType;
import com.dating.im.entity.ChatMessageEntity;
import com.dating.im.manager.MessageManager;
import com.dating.im.model.ImEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 消息发送后处理器.
 *
 * <p>消息已成功发出后触发: 落库 + AI 回复路由.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageSentHandler {

    private final MessageManager messageManager;
    private final UserServiceClient userServiceClient;
    private final AiReplyService aiReplyService;

    /**
     * 处理 after-send 事件.
     *
     * @param event 消息已发送事件
     */
    public void handle(ImEvent.MessageSentEvent event) {
        Long fromUserId = event.getFromUserId();
        Long toUserId = event.getToUserId();

        // 1. 落库
        saveMessage(event);

        // 2. 路由判断
        if (fromUserId == null || toUserId == null) {
            log.warn("Invalid user in messageSent, skipping");
            return;
        }

        boolean fromIsDh = userServiceClient.isDigitalHuman(fromUserId);
        boolean toIsDh = userServiceClient.isDigitalHuman(toUserId);
        String routeType = messageManager.determineRouteType(fromUserId, toUserId, fromIsDh, toIsDh);

        log.debug("Message route: {} -> {}, route={}", fromUserId, toUserId, routeType);

        // 3. BH -> DH: 触发 AI 回复
        if (RouteType.BH_DH.equals(routeType)) {
            aiReplyService.triggerAiReply(event, fromUserId, toUserId);
        }
    }

    private void saveMessage(ImEvent.MessageSentEvent event) {
        try {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setMessageId(event.getMessageId());
            entity.setFromUserId(event.getFromUserId());
            entity.setToUserId(event.getToUserId());
            entity.setContent(event.getContent());
            entity.setType(event.getMsgType());
            entity.setConversationType(event.getConversationType());
            entity.setProvider(event.getProvider());
            entity.setTimestamp(event.getTimestamp());
            entity.setCreatedAt(OffsetDateTime.now());

            messageManager.save(entity);
        } catch (Exception e) {
            log.error("Failed to save message: messageId={}", event.getMessageId(), e);
        }
    }
}
