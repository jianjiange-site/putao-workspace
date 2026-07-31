package com.dating.match.service;

import com.dating.match.constant.DelayedTaskStatusConst;
import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.DelayedMatchTaskEntity;
import com.dating.match.manager.DelayedMatchTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Durable delayed match queue backed by PostgreSQL. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhDelayedMatchService {
    private static final long MIN_DELAY_MS = 15_000L;
    private static final long MAX_DELAY_MS = 120_000L;
    private static final int SCAN_LIMIT = 100;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final DelayedMatchTaskManager taskManager;
    private final MatchService matchService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final String workerId = UUID.randomUUID().toString();

    /**
     * Called inside the swipe database transaction, so the swipe row and delayed
     * task commit or roll back together.
     */
    public void scheduleDelayedMatch(long userId, long dhId) {
        long delayMs = ThreadLocalRandom.current()
                .nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);
        Instant executeAt = Instant.now().plusMillis(delayMs);
        DelayedMatchTaskEntity task = new DelayedMatchTaskEntity();
        task.setUserId(userId);
        task.setDhUserId(dhId);
        task.setSource(MatchSourceConst.SWIPE_MATCH);
        task.setExecuteAt(executeAt);
        task.setNextRetryAt(executeAt);
        task.setAttempts(0);
        task.setStatus(DelayedTaskStatusConst.PENDING);
        taskManager.enqueue(task);
    }

    public int deliverDueTasks() {
        Instant now = Instant.now();
        List<DelayedMatchTaskEntity> tasks = taskManager.claimDue(
                now, SCAN_LIMIT, workerId, now.plus(LEASE));
        int delivered = 0;
        for (DelayedMatchTaskEntity task : tasks) {
            try {
                int type = userServiceClient.getUserType(task.getDhUserId());
                if (type < 0) {
                    throw new IllegalStateException("user-service unavailable");
                }
                if (type == UserTypeConst.DH) {
                    matchService.createMatch(task.getUserId(), task.getDhUserId(), task.getSource());
                }
                taskManager.markDone(task.getId(), workerId);
                delivered++;
            } catch (Exception e) {
                int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
                long delay = Math.min(300, (long) Math.pow(2, attempts + 1) * 5);
                taskManager.markRetry(task.getId(), workerId,
                        Instant.now().plusSeconds(delay), MAX_ATTEMPTS, e.getMessage());
                log.warn("Delayed match failed taskId={} userId={} dhId={} err={}",
                        task.getId(), task.getUserId(), task.getDhUserId(), e.getMessage());
            }
        }
        return delivered;
    }
}
