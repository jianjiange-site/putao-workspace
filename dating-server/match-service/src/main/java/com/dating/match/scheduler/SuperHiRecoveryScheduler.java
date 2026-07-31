package com.dating.match.scheduler;

import com.dating.match.service.SuperHiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SuperHiRecoveryScheduler {
    private final SuperHiService superHiService;

    @Scheduled(fixedDelay = 30_000L)
    public void run() {
        try {
            superHiService.recoverPendingOperations();
        } catch (Exception e) {
            log.error("Super Hi recovery scan failed", e);
        }
    }
}
