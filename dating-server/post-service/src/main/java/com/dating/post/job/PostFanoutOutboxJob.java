package com.dating.post.job;

import com.dating.post.service.PostFanoutOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostFanoutOutboxJob {

    private final PostFanoutOutboxService outboxService;

    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(
            name = "post.fanoutOutbox",
            lockAtMostFor = "PT30S",
            lockAtLeastFor = "PT1S"
    )
    public void deliver() {
        int delivered = outboxService.deliverDue();
        if (delivered > 0) {
            log.info("Fanout outbox delivered: count={}", delivered);
        }
    }
}
