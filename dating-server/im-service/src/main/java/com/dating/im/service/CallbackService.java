package com.dating.im.service;

import com.dating.im.adaptor.ImProviderAdaptorManager;
import com.dating.im.model.ImEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 回调服务.
 *
 * <p>接收 mobile-gateway 透传的 OpenIM 回调,分发给对应 handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final ImProviderAdaptorManager adaptorManager;
    private final BeforeSendHandler beforeSendHandler;
    private final MessageSentHandler messageSentHandler;
    private final PresenceService presenceService;

    /**
     * 处理原始回调.
     *
     * @param provider   IM 引擎标识 (openim/tencent)
     * @param rawPayload 原始 JSON
     * @return 业务决策码: 0=允许,非0=拒绝
     */
    public int handleRawCallback(String provider, byte[] rawPayload) {
        ImEvent event = adaptorManager.parse(provider, rawPayload);

        return switch (event) {
            case ImEvent.MessageBeforeSendEvent e -> {
                log.info("Processing beforeSend: from={}, to={}, msgId={}",
                        e.getFromUserId(), e.getToUserId(), e.getMessageId());
                yield beforeSendHandler.handle(e);
            }
            case ImEvent.MessageSentEvent e -> {
                log.info("Processing afterSend: from={}, to={}, msgId={}",
                        e.getFromUserId(), e.getToUserId(), e.getMessageId());
                messageSentHandler.handle(e);
                yield 0;
            }
            case ImEvent.UserOnlineEvent e -> {
                log.info("User online: userId={}, platform={}",
                        e.getUserId(), e.getPlatform());
                presenceService.online(e.getUserId(), e.getPlatform(), e.getOnlineAt());
                yield 0;
            }
            case ImEvent.UserOfflineEvent e -> {
                log.info("User offline: userId={}, platform={}",
                        e.getUserId(), e.getPlatform());
                presenceService.offline(e.getUserId(), e.getPlatform(), e.getOfflineAt());
                yield 0;
            }
            case ImEvent.UnknownEvent e -> {
                log.info("Unknown event: type={}, provider={}", e.getType(), e.getProvider());
                yield 0;
            }
        };
    }
}
