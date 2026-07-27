package com.dating.match.client;

import com.dating.im.proto.EnsureConversationRequest;
import com.dating.im.proto.EnsureConversationResponse;
import com.dating.im.proto.ImServiceGrpc;
import com.dating.im.proto.ListOnlineUsersRequest;
import com.dating.im.proto.ListOnlineUsersResponse;
import com.dating.im.proto.ListRecentOfflineUsersRequest;
import com.dating.im.proto.ListRecentOfflineUsersResponse;
import com.dating.im.proto.SendSystemMessageRequest;
import com.dating.im.proto.SendSystemMessageResponse;
import com.dating.im.proto.TriggerDhOpeningRequest;
import com.dating.im.proto.TriggerDhOpeningResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * IM Service gRPC Client.
 *
 * <p>match-service 通过本 Client 调 im-service 建会话 / 推系统消息 / 触发 DH 开场白.
 * 跨服务失败由 outbox 兜底重试.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImServiceClient {

    private final ImServiceGrpc.ImServiceBlockingStub imServiceBlockingStub;

    /**
     * 建会话(幂等).
     *
     * @return conversation_id;失败时抛 RuntimeException,由 outbox 兜底
     */
    public String ensureConversation(long userIdA, long userIdB) {
        EnsureConversationRequest req = EnsureConversationRequest.newBuilder()
                .setUserIdA(userIdA)
                .setUserIdB(userIdB)
                .build();
        EnsureConversationResponse resp = imServiceBlockingStub.ensureConversation(req);
        return resp.getConversationId();
    }

    /**
     * 发送系统消息(配对成功通知).
     */
    public String sendSystemMessage(long toUserId, String content, String messageType) {
        SendSystemMessageRequest req = SendSystemMessageRequest.newBuilder()
                .setToUserId(toUserId)
                .setContent(content)
                .setMessageType(messageType)
                .build();
        SendSystemMessageResponse resp = imServiceBlockingStub.sendSystemMessage(req);
        return resp.getMessageId();
    }

    /**
     * 触发 DH 主动开场白(DH 端 AI 生成第一条消息).
     */
    public boolean triggerDhOpening(long dhUserId, long targetUserId, String conversationId) {
        TriggerDhOpeningRequest req = TriggerDhOpeningRequest.newBuilder()
                .setDhUserId(dhUserId)
                .setTargetUserId(targetUserId)
                .setConversationId(conversationId)
                .build();
        TriggerDhOpeningResponse resp = imServiceBlockingStub.triggerDhOpening(req);
        return resp.getAccepted();
    }

    /**
     * 列指定时间窗内新上线的真人用户.
     *
     * @param sinceMs 闭区间下界 epoch ms
     * @param untilMs 开区间上界 epoch ms
     * @param limit   上限
     */
    public List<Long> listOnlineUsers(long sinceMs, long untilMs, int limit) {
        try {
            ListOnlineUsersRequest req = ListOnlineUsersRequest.newBuilder()
                    .setSince(sinceMs)
                    .setUntil(untilMs)
                    .setLimit(limit)
                    .build();
            ListOnlineUsersResponse resp = imServiceBlockingStub.listOnlineUsers(req);
            return resp.getUserIdsList();
        } catch (Exception e) {
            log.warn("im-service.listOnlineUsers failed, err={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 列指定时间窗内下线的真人用户(读 user_online_session).
     */
    public List<Long> listRecentOfflineUsers(long sinceMs, long untilMs, int limit) {
        try {
            ListRecentOfflineUsersRequest req = ListRecentOfflineUsersRequest.newBuilder()
                    .setSince(sinceMs)
                    .setUntil(untilMs)
                    .setLimit(limit)
                    .build();
            ListRecentOfflineUsersResponse resp = imServiceBlockingStub.listRecentOfflineUsers(req);
            return resp.getUserIdsList();
        } catch (Exception e) {
            log.warn("im-service.listRecentOfflineUsers failed, err={}", e.getMessage());
            return Collections.emptyList();
        }
    }
}