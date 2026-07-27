package com.dating.match.scheduler;

import com.dating.match.service.MatchOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * match_outbox 副作用重试调度(7.5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchOutboxRetry {

    private final MatchOutboxService outboxService;

    @Scheduled(fixedDelay = 30_000L)
    public void run() {
        try {
            int delivered = outboxService.deliver();
            if (delivered > 0) {
                log.debug("Outbox retry delivered={}", delivered);
            }
        } catch (Exception e) {
            log.error("Outbox retry failed", e);
        }
    }
}