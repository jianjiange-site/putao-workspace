package com.dating.match.scheduler;

import com.dating.match.constant.MatchRedisKey;
import com.dating.match.service.DhInteractionPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LikeVisitorTaskExecutor(7.5) — 每 1 分钟扫到期待执行任务并执行 UPSERT + 硬删.
 *
 * <p>Redisson 锁 {@code lock:match:dh_plan:executor}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeVisitorTaskExecutor {

    private final DhInteractionPlanService planService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 60_000L)
    public void run() {
        RLock lock = redissonClient.getLock(MatchRedisKey.LOCK_DH_PLAN_EXECUTOR);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            log.debug("LikeVisitorTaskExecutor lock not acquired, skip");
            return;
        }
        try {
            // 执行任务
            // 调用planService.runExecutor()，执行任务
            int n = planService.runExecutor();
            // 如果执行的任务数量大于0，则记录日志
            if (n > 0) {
                log.info("LikeVisitorTaskExecutor executed {} tasks", n);
            }
        } catch (Exception e) {
            log.error("LikeVisitorTaskExecutor failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}