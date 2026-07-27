package com.dating.match.service;

import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.UserTypeConst;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DH 延迟匹配调度(5.2).
 *
 * <p>用 Spring TaskScheduler 进程内调度,15s ~ 2min 均匀随机分布.
 * 任务只是触发 match,具体 match 创建走 {@link MatchService#createMatch}.
 *
 * <p>trade-off:服务重启会丢失 in-flight 任务(PRD 5.2 已确认接受).
 */
@Slf4j
@Service
public class DhDelayedMatchService {

    /** 最小延迟 15s. */
    private static final long MIN_DELAY_MS = 15_000L;

    /** 最大延迟 2min. */
    private static final long MAX_DELAY_MS = 120_000L;

    private final MatchService matchService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final TaskScheduler matchTaskScheduler;

    public DhDelayedMatchService(MatchService matchService,
                                  com.dating.match.client.UserServiceClient userServiceClient,
                                  @Qualifier("matchTaskScheduler") TaskScheduler matchTaskScheduler) {
        this.matchService = matchService;
        this.userServiceClient = userServiceClient;
        this.matchTaskScheduler = matchTaskScheduler;
    }

    /**
     * 挂一个 15s~2min 后的延迟匹配任务.
     *
     * @param userId 划卡方(BH)
     * @param dhId   DH 端 user_id
     */
    public void scheduleDelayedMatch(long userId, long dhId) {
        // 随机生成 15s~2min 后的延迟时间
        long delayMs = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);
        // 计算触发时间
        Instant fireAt = Instant.now().plusMillis(delayMs);
        // 记录日志
        log.debug("Schedule delayed match userId={} dhId={} fireAt={} (delay {}ms)",
                userId, dhId, fireAt, delayMs);

        // 调度任务
        matchTaskScheduler.schedule(() -> {
            try {
                // 触发前再校验一次 target 仍为 DH(可能注销)
                int type = userServiceClient.getUserType(dhId);
                if (type != UserTypeConst.DH) {
                    log.debug("Delayed match skip: target no longer DH userId={} dhId={}", userId, dhId);
                    return;
                }
                // 创建匹配
                matchService.createMatch(userId, dhId, MatchSourceConst.SWIPE_MATCH);
                // 记录日志
                log.info("Delayed match created userId={} dhId={}", userId, dhId);
            } catch (Exception e) {
                log.error("Delayed match failed userId={} dhId={} err={}", userId, dhId, e.getMessage(), e);
            }
        }, fireAt);
    }
}