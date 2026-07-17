package com.dating.im.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 回复服务.
 *
 * <p>编排 AI 回复流程: 生成回复 + 分段发送 + typing 续命.
 */
@Slf4j
@Service
public class AiReplyService {

    private final NotificationService notificationService;

    public AiReplyService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 触发 AI 回复.
     *
     * @param event     原始消息事件
     * @param fromUserId BH(真人) ID
     * @param toUserId   DH(数字人) ID
     */
    public void triggerAiReply(com.dating.im.model.ImEvent.MessageSentEvent event,
                               Long fromUserId, Long toUserId) {
        log.info("Triggering AI reply: BH={} -> DH={}", fromUserId, toUserId);

        // TODO: 实现完整的 AI 回复流程
        // 1. 调 ai-chat 生成回复
        // 2. 维持 typing 信号
        // 3. 按打字节奏分段发送

        // 占位实现
        try {
            // 模拟延迟
            Thread.sleep(2000);

            // 发送一个测试回复
            String replyContent = "Hello! This is an AI reply.";
            notificationService.sendBusinessNotification(
                    toUserId.toString(),
                    fromUserId.toString(),
                    "text",
                    java.util.Map.of("content", replyContent)
            );
        } catch (Exception e) {
            log.error("Failed to send AI reply", e);
        }
    }
}
