package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.OutboxActionConst;
import com.dating.match.entity.MatchOutboxEntity;
import com.dating.match.manager.MatchOutboxManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Multi-instance-safe outbox dispatcher. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchOutboxService {
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final MatchOutboxManager outboxManager;
    private final com.dating.match.client.ImServiceClient imServiceClient;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final ObjectMapper objectMapper;
    private final MatchProperties props;
    private final String workerId = UUID.randomUUID().toString();

    public int deliver() {
        Instant now = Instant.now();
        List<MatchOutboxEntity> pending = outboxManager.claimPending(
                now, props.getOutboxScanLimit(), workerId, now.plus(LEASE));
        int delivered = 0;
        for (MatchOutboxEntity task : pending) {
            try {
                dispatch(task);
                outboxManager.markDone(task.getId(), workerId);
                delivered++;
            } catch (Exception e) {
                log.warn("Outbox dispatch failed id={} eventKey={} action={} err={}",
                        task.getId(), task.getEventKey(), task.getAction(), e.getMessage());
                scheduleRetry(task);
            }
        }
        return delivered;
    }

    private void dispatch(MatchOutboxEntity task) {
        JsonNode payload = parse(task.getPayloadJson());
        switch (task.getAction()) {
            case OutboxActionConst.ENSURE_CONVERSATION -> {
                long userIdA = requiredLong(payload, "user_id_a");
                long userIdB = requiredLong(payload, "user_id_b");
                imServiceClient.ensureConversation(userIdA, userIdB);
            }
            case OutboxActionConst.SYSTEM_MSG -> {
                long toUserId = requiredLong(payload, "to_user_id");
                String content = payload.path("content").asText();
                String type = payload.path("message_type").asText("MATCH_CREATED");
                imServiceClient.sendSystemMessage(toUserId, content, type);
            }
            case OutboxActionConst.DH_OPENING -> dispatchDhOpening(payload);
            default -> throw new IllegalArgumentException("Unknown outbox action " + task.getAction());
        }
    }

    private void dispatchDhOpening(JsonNode payload) {
        long userIdA = requiredLong(payload, "user_id_a");
        long userIdB = requiredLong(payload, "user_id_b");
        int typeA = userServiceClient.getUserType(userIdA);
        int typeB = userServiceClient.getUserType(userIdB);
        long dhUserId;
        long targetUserId;
        if (typeA == com.dating.match.constant.UserTypeConst.DH
                && typeB == com.dating.match.constant.UserTypeConst.BH) {
            dhUserId = userIdA;
            targetUserId = userIdB;
        } else if (typeB == com.dating.match.constant.UserTypeConst.DH
                && typeA == com.dating.match.constant.UserTypeConst.BH) {
            dhUserId = userIdB;
            targetUserId = userIdA;
        } else if (typeA < 0 || typeB < 0) {
            throw new IllegalStateException("Cannot resolve user types for DH opening");
        } else {
            return;
        }
        String conversationId = imServiceClient.ensureConversation(userIdA, userIdB);
        if (!imServiceClient.triggerDhOpening(dhUserId, targetUserId, conversationId)) {
            throw new IllegalStateException("IM rejected DH opening");
        }
    }

    private long requiredLong(JsonNode payload, String name) {
        long value = payload.path(name).asLong(0L);
        if (value <= 0) {
            throw new IllegalArgumentException("Missing outbox payload field " + name);
        }
        return value;
    }

    private void scheduleRetry(MatchOutboxEntity task) {
        int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
        long delay = (long) Math.min(1800, Math.pow(2, attempts + 1) * 5);
        outboxManager.markRetry(task.getId(), workerId,
                Instant.now().plusSeconds(delay), props.getOutboxMaxAttempts());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid outbox payload", e);
        }
    }
}
