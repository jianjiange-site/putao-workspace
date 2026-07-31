package com.dating.match.service;

import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.OutboxActionConst;
import com.dating.match.constant.OutboxStatusConst;
import com.dating.match.entity.MatchEntity;
import com.dating.match.entity.MatchOutboxEntity;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.MatchOutboxManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Creates the authoritative match and its side-effect promises in one transaction. */
@Service
@RequiredArgsConstructor
public class MatchService {
    private final MatchManager matchManager;
    private final LikeRecordManager likeRecordManager;
    private final MatchOutboxManager outboxManager;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public MatchEntity createMatch(long userA, long userB, String source) {
        MatchManager.InsertResult result =
                matchManager.insertIgnoreConflictWithLog(userA, userB, source);
        if (!result.success() || result.match() == null) {
            return result.existing();
        }

        MatchEntity match = result.match();
        likeRecordManager.softDeleteByPair(userA, userB);
        enqueueSideEffects(match.getId(), userA, userB, source);
        return match;
    }

    public Optional<MatchEntity> findByPair(long low, long high) {
        return matchManager.findByPair(low, high);
    }

    private void enqueueSideEffects(long matchId, long userA, long userB, String source) {
        enqueue(matchId, matchId + ":conversation", OutboxActionConst.ENSURE_CONVERSATION,
                Map.of("user_id_a", userA, "user_id_b", userB));

        enqueue(matchId, matchId + ":system:" + userA, OutboxActionConst.SYSTEM_MSG,
                systemMessagePayload(userA, userB, source));
        enqueue(matchId, matchId + ":system:" + userB, OutboxActionConst.SYSTEM_MSG,
                systemMessagePayload(userB, userA, source));

        if (MatchSourceConst.SWIPE_SUPER_HI.equals(source)
                || MatchSourceConst.SWIPE_MATCH.equals(source)) {
            enqueue(matchId, matchId + ":dh-opening", OutboxActionConst.DH_OPENING,
                    Map.of("user_id_a", userA, "user_id_b", userB));
        }
    }

    private Map<String, Object> systemMessagePayload(long toUserId, long fromUserId, String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to_user_id", toUserId);
        payload.put("from_user_id", fromUserId);
        payload.put("content", "你们配对了,开始聊天吧!");
        payload.put("message_type", "MATCH_CREATED");
        payload.put("source", source);
        return payload;
    }

    private void enqueue(long matchId, String eventKey, String action, Map<String, Object> payload) {
        MatchOutboxEntity entity = new MatchOutboxEntity();
        entity.setMatchId(matchId);
        entity.setEventKey(eventKey);
        entity.setAction(action);
        entity.setPayloadJson(toJson(payload));
        entity.setAttempts(0);
        entity.setNextRetryAt(Instant.now());
        entity.setStatus(OutboxStatusConst.PENDING);
        outboxManager.enqueue(entity);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize match outbox payload", e);
        }
    }
}
