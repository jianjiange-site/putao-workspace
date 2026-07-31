package com.dating.match.scheduler;

import com.dating.match.service.DhDelayedMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedMatchTaskExecutor {
    private final DhDelayedMatchService delayedMatchService;

    @Scheduled(fixedDelay = 5_000L)
    public void run() {
        try {
            delayedMatchService.deliverDueTasks();
        } catch (Exception e) {
            log.error("Delayed match task scan failed", e);
        }
    }
}
