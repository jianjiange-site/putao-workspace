package com.dating.match.manager;

import com.dating.match.entity.DelayedMatchTaskEntity;
import com.dating.match.mapper.DelayedMatchTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DelayedMatchTaskManager {
    private final DelayedMatchTaskMapper mapper;

    public void enqueue(DelayedMatchTaskEntity entity) {
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // The swipe row and unique task key make scheduling idempotent.
        }
    }

    public List<DelayedMatchTaskEntity> claimDue(Instant now, int limit,
                                                  String workerId, Instant leaseUntil) {
        return mapper.claimDue(now, limit, workerId, leaseUntil);
    }

    public void markDone(Long id, String workerId) {
        mapper.markDone(id, workerId);
    }

    public void markRetry(Long id, String workerId, Instant nextRetryAt,
                          int maxAttempts, String lastError) {
        mapper.markRetry(id, workerId, nextRetryAt, maxAttempts, truncate(lastError));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
