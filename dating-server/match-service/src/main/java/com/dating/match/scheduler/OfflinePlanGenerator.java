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
 * OfflinePlanGenerator(7.5) — 每 20 分钟扫离线用户,生成 DH 互动任务.
 *
 * <p>Redisson 锁 {@code lock:match:dh_plan:offline_sweep}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflinePlanGenerator {

    private final DhInteractionPlanService planService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 1_200_000L) // 20 min
    public void run() {
        RLock lock = redissonClient.getLock(MatchRedisKey.LOCK_DH_PLAN_OFFLINE);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 60 * 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            log.debug("OfflinePlanGenerator lock not acquired, skip");
            return;
        }
        try {
            int n = planService.runOfflinePlan();
            if (n > 0) {
                log.info("OfflinePlanGenerator generated {} tasks", n);
            }
        } catch (Exception e) {
            log.error("OfflinePlanGenerator failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}