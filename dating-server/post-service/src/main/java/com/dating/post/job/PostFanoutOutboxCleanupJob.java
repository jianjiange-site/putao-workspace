package com.dating.post.job;

import com.dating.post.mapper.PostFanoutOutboxCleanupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostFanoutOutboxCleanupJob {

    private final PostFanoutOutboxCleanupMapper cleanupMapper;

    @Scheduled(fixedDelay = 3_600_000)
    @SchedulerLock(
            name = "post.fanoutOutboxCleanup",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT10S"
    )
    public void cleanup() {
        int deleted = cleanupMapper.deleteDeliveredBatch(10_000);
        if (deleted > 0) {
            log.info("Fanout outbox cleanup completed: deleted={}", deleted);
        }
    }
}
