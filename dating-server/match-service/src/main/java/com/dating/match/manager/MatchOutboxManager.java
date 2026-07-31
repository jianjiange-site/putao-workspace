package com.dating.match.manager;

import com.dating.match.entity.MatchOutboxEntity;
import com.dating.match.mapper.MatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MatchOutboxManager {
    private final MatchOutboxMapper outboxMapper;

    public void enqueue(MatchOutboxEntity entity) {
        outboxMapper.insert(entity);
    }

    public List<MatchOutboxEntity> claimPending(Instant now, int limit,
                                                 String workerId, Instant leaseUntil) {
        return outboxMapper.claimPending(now, limit, workerId, leaseUntil);
    }

    public void markDone(Long id, String workerId) {
        outboxMapper.markDone(id, workerId);
    }

    public void markRetry(Long id, String workerId,
                          Instant nextRetryAt, int maxAttempts) {
        outboxMapper.markRetry(id, workerId, nextRetryAt, maxAttempts);
    }
}
