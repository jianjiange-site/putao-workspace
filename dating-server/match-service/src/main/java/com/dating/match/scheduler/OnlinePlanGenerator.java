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
 * OnlinePlanGenerator(7.5) — 每 1 分钟扫在线用户,生成 DH 互动任务.
 *
 * <p>Redisson 锁 {@code lock:match:dh_plan:online_sweep}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlinePlanGenerator {

    private final DhInteractionPlanService planService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelay = 60_000L)
    public void run() {
        // 获取锁
        RLock lock = redissonClient.getLock(MatchRedisKey.LOCK_DH_PLAN_ONLINE);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // 如果获取锁失败，则直接返回
        if (!acquired) {
            log.debug("OnlinePlanGenerator lock not acquired, skip");
            return;
        }
        try {
            // 生成DH互动任务
            int n = planService.runOnlinePlan();
            // 如果生成的DH互动任务数量大于0，则记录日志
            if (n > 0) {
                log.info("OnlinePlanGenerator generated {} tasks", n);
            }
        } catch (Exception e) {
            log.error("OnlinePlanGenerator failed", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}