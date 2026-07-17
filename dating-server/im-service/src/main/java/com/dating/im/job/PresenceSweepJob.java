package com.dating.im.job;

import com.dating.im.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在线状态清扫任务.
 *
 * <p>定时清扫孤儿会话(只上线没下线).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceSweepJob {

    private final PresenceService presenceService;

    @Value("${im.presence.sweep.max-online-hours:26}")
    private int maxOnlineHours;

    @Value("${im.presence.sweep.enabled:true}")
    private boolean enabled;

    /**
     * 孤儿会话清扫.
     *
     * <p>每 30 分钟执行一次,找出 score < (now - maxOnlineHours) 的会话,
     * 按阈值封顶时长 closeSession.
     */
    @Scheduled(cron = "${im.presence.sweep.cron:0 */30 * * * *}")
    @SchedulerLock(name = "presenceSweep", lockAtMostFor = "PT5M")
    public void sweep() {
        if (!enabled) {
            log.debug("Presence sweep disabled, skipping");
            return;
        }

        log.info("Starting presence sweep: maxOnlineHours={}", maxOnlineHours);

        try {
            presenceService.sweepOrphanSessions(maxOnlineHours);
            log.info("Presence sweep completed");
        } catch (Exception e) {
            log.error("Presence sweep failed", e);
        }
    }
}
