package com.dating.match.manager;

import com.dating.match.entity.MatchOutboxEntity;
import com.dating.match.mapper.MatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * match_outbox Manager.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchOutboxManager {

    private final MatchOutboxMapper outboxMapper;

    /**
     * 写入一条 outbox 任务.
     */
    public void enqueue(MatchOutboxEntity entity) {
        outboxMapper.insert(entity);
    }

    /**
     * 拉取到期任务.
     */
    public List<MatchOutboxEntity> listPending(Instant now, int limit) {
        return outboxMapper.listPending(now, limit);
    }

    /**
     * 标记 DONE.
     */
    public void markDone(Long id) {
        outboxMapper.markDone(id);
    }

    /**
     * 标记失败并推进 next_retry_at,达到 max_attempts 自动转 DEAD.
     */
    public void markRetry(Long id, Instant nextRetryAt, int maxAttempts) {
        outboxMapper.markRetry(id, nextRetryAt, maxAttempts);
    }
}
