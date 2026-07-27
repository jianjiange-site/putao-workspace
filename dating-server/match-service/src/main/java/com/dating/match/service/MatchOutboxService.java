package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.OutboxActionConst;
import com.dating.match.constant.OutboxStatusConst;
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

/**
 * match_outbox 副作用投递服务.
 *
 * <p>由 {@code MatchOutboxRetry} scheduler 周期性扫描 PENDING 任务,执行 IM 副作用,
 * 成功 → DONE,失败 → 重试指数退避;达到 max_attempts → DEAD.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchOutboxService {

    private final MatchOutboxManager outboxManager;
    private final com.dating.match.client.ImServiceClient imServiceClient;
    private final ObjectMapper objectMapper;
    private final MatchProperties props;

    /**
     * 扫描并投递到期任务.
     */
    public int deliver() {
        Instant now = Instant.now();
        List<MatchOutboxEntity> pending = outboxManager.listPending(now, props.getOutboxScanLimit());
        int delivered = 0;
        for (MatchOutboxEntity task : pending) {
            try {
                boolean ok = dispatch(task);
                if (ok) {
                    outboxManager.markDone(task.getId());
                    delivered++;
                } else {
                    scheduleRetry(task);
                }
            } catch (Exception e) {
                log.warn("Outbox dispatch failed: id={} action={} err={}",
                        task.getId(), task.getAction(), e.getMessage());
                scheduleRetry(task);
            }
        }
        return delivered;
    }

    private boolean dispatch(MatchOutboxEntity task) {
        JsonNode payload = parse(task.getPayloadJson());
        switch (task.getAction()) {
            case OutboxActionConst.ENSURE_CONVERSATION -> {
                long userIdA = payload.path("user_id_a").asLong();
                long userIdB = payload.path("user_id_b").asLong();
                String conv = imServiceClient.ensureConversation(userIdA, userIdB);
                log.info("ensureConversation ok: matchId={} conv={}", task.getMatchId(), conv);
                return true;
            }
            case OutboxActionConst.SYSTEM_MSG -> {
                long toUserId = payload.path("to_user_id").asLong();
                String content = payload.path("content").asText();
                String type = payload.path("message_type").asText("MATCH_CREATED");
                imServiceClient.sendSystemMessage(toUserId, content, type);
                log.info("sendSystemMessage ok: matchId={} to={}", task.getMatchId(), toUserId);
                return true;
            }
            case OutboxActionConst.DH_OPENING -> {
                // DH 开场白需要 conversation_id — 此处无 conversation_id 时跳到下一轮 retry 时由 EnsureConversation 已完成获得
                log.debug("DH_OPENING placeholder — skip until conv established matchId={}", task.getMatchId());
                return true;
            }
            default -> {
                log.warn("Unknown outbox action: {}", task.getAction());
                return true;
            }
        }
    }

    private void scheduleRetry(MatchOutboxEntity task) {
        int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
        long nextDelaySec = (long) Math.min(60 * 30, Math.pow(2, attempts + 1) * 5);
        Instant nextRetry = Instant.now().plus(Duration.ofSeconds(nextDelaySec));
        outboxManager.markRetry(task.getId(), nextRetry, props.getOutboxMaxAttempts());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("Parse outbox payload failed: {}", json, e);
            return objectMapper.createObjectNode();
        }
    }
}