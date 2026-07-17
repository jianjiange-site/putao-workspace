package com.dating.im.manager;

import com.dating.im.entity.ChatMessageEntity;
import com.dating.im.mapper.ChatMessageMapper;
import com.dating.im.constant.MessageType;
import com.dating.im.constant.RouteType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 消息管理器.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageManager {

    private final ChatMessageMapper messageMapper;

    /**
     * 保存消息.
     */
    public ChatMessageEntity save(ChatMessageEntity message) {
        messageMapper.insert(message);
        log.debug("Message saved: messageId={}", message.getMessageId());
        return message;
    }

    /**
     * 根据 messageId 查询消息.
     */
    public ChatMessageEntity findByMessageId(String messageId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMessageId, messageId)
        );
    }

    /**
     * 生成消息ID.
     */
    public String generateMessageId(String serverMsgId, String provider) {
        return provider + "_" + serverMsgId;
    }

    /**
     * 判断路由类型.
     */
    public String determineRouteType(Long fromUserId, Long toUserId, boolean fromIsDh, boolean toIsDh) {
        if (fromIsDh && toIsDh) {
            return RouteType.DH_DH;
        } else if (fromIsDh) {
            return RouteType.DH_BH;
        } else if (toIsDh) {
            return RouteType.BH_DH;
        } else {
            return RouteType.BH_BH;
        }
    }
}
