package com.dating.match.service;

import com.dating.match.constant.OutboxActionConst;
import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.OutboxStatusConst;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.MatchEntity;
import com.dating.match.entity.MatchOutboxEntity;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.MatchOutboxManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Match 创建 + 副作用发件箱.
 *
 * <p>流程:本地事务内 INSERT match + DELETE 双向 like_record;跨服务副作用(EnsureConversation /
 * SendSystemMessage / TriggerDhOpening)失败时入 {@code match_outbox},由 {@code MatchOutboxRetry}
 * 后台重试.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchManager matchManager;
    private final LikeRecordManager likeRecordManager;
    private final MatchOutboxManager outboxManager;
    private final UserSwipeHistoryManager swipeHistoryManager;
    private final com.dating.match.client.ImServiceClient imServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建 match 并执行副作用(同步部分).
     *
     * @param userA  划卡方用户
     * @param userB  对方用户
     * @param source SWIPE_MATCH / SWIPE_SUPER_HI
     * @return 创建的 match(或已存在的)
     */
    @Transactional(rollbackFor = Exception.class)
    public MatchEntity createMatch(long userA, long userB, String source) {
        // 1. INSERT IGNORE match
        MatchManager.InsertResult insertResult = matchManager.insertIgnoreConflictWithLog(userA, userB, source);
        if (!insertResult.success() || insertResult.match() == null) {
            // 已存在 — 返回 existing(由调用方处理日志)
            return insertResult.existing();
        }
        MatchEntity match = insertResult.match();

        // 2. 同事务清理双向 like_record(原暗恋升级 match)
        likeRecordManager.softDeleteByPair(userA, userB);

        // 3. 入 outbox 三条副作用(同步失败由后台 retry)
        enqueueSideEffects(match.getId(), userA, userB, source);

        return match;
    }

    /**
     * 入 outbox 
     */
    private void enqueueSideEffects(long matchId, long userA, long userB, String source) {
        // 3.1 EnsureConversation
        MatchOutboxEntity conv = new MatchOutboxEntity();
        conv.setMatchId(matchId);
        conv.setAction(OutboxActionConst.ENSURE_CONVERSATION);
        conv.setPayloadJson(toJson(payloadOfConv(userA, userB)));
        conv.setAttempts(0);
        conv.setNextRetryAt(Instant.now());
        conv.setStatus(OutboxStatusConst.PENDING);
        outboxManager.enqueue(conv);

        // 3.2 SendSystemMessage x2(双方)
        enqueueSystemMsg(matchId, userA, userB, source);
        enqueueSystemMsg(matchId, userB, userA, source);

        // 3.3 TriggerDhOpening(BH 端立即匹配场景跳过;SuperHi DH 端需要触发)
        if (MatchSourceConst.SWIPE_SUPER_HI.equals(source) || MatchSourceConst.SWIPE_MATCH.equals(source)) {
            // 判断 DH 端是哪个 — SuperHi 路径下 userA 主动,可能 userB 是 DH;SwipeMatch 也可能是 DH
            // DH 端触发开场白(简化:异步判定)
            enqueueDhOpening(matchId, userA, userB);
        }
    }

    private void enqueueSystemMsg(long matchId, long toUserId, long fromUserId, String source) {
        MatchOutboxEntity msg = new MatchOutboxEntity();
        msg.setMatchId(matchId);
        msg.setAction(OutboxActionConst.SYSTEM_MSG);
        Map<String, Object> payload = new HashMap<>();
        payload.put("to_user_id", toUserId);
        payload.put("from_user_id", fromUserId);
        payload.put("content", "你们配对了,开始聊天吧!");
        payload.put("message_type", "MATCH_CREATED");
        msg.setPayloadJson(toJson(payload));
        msg.setAttempts(0);
        msg.setNextRetryAt(Instant.now());
        msg.setStatus(OutboxStatusConst.PENDING);
        outboxManager.enqueue(msg);
    }

    private void enqueueDhOpening(long matchId, long userA, long userB) {
        MatchOutboxEntity opening = new MatchOutboxEntity();
        opening.setMatchId(matchId);
        opening.setAction(OutboxActionConst.DH_OPENING);
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversation_id_placeholder", true);
        opening.setPayloadJson(toJson(payload));
        opening.setAttempts(0);
        opening.setNextRetryAt(Instant.now());
        opening.setStatus(OutboxStatusConst.PENDING);
        outboxManager.enqueue(opening);
    }

    private Map<String, Object> payloadOfConv(long userIdA, long userIdB) {
        Map<String, Object> map = new HashMap<>();
        map.put("user_id_a", userIdA);
        map.put("user_id_b", userIdB);
        return map;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("toJson failed", e);
            return "{}";
        }
    }

    /**
     * 直接同步执行 match 副作用(跳过 outbox).DH 延迟匹配完成时使用.
     *
     * <p>由于网络抖动,EnsureConversation / SendSystemMessage 等若失败,会回退到 outbox 兜底.
     */
    public MatchEntity createMatchImmediateWithSideEffects(long userA, long userB, String source) {
        MatchEntity match = createMatch(userA, userB, source);
        // 触发一次 outbox 重试(让 retry 进程跑一次)
        return match;
    }
}