package com.dating.post.job;

import com.dating.post.service.FeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FeedScoreJob.
 *
 * <p>每 5 分钟重建全网热门池.
 *
 * <p>核心流程:
 * 1. 捞取近3天所有 status=1 帖子
 * 2. 批量获取计数(基准值 + Redis增量)
 * 3. 内存用 Hacker News 公式打分
 * 4. 性别分桶
 * 5. 写影子ZSet
 * 6. 原子 RENAME
 *
 * <p>ShedLock 多实例互斥: lockAtMostFor="PT10M", lockAtLeastFor="PT1M"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedScoreJob {

    private final FeedService feedService;

    /**
     * 热门池重建任务.
     *
     * <p>每 5 分钟执行一次.
     */
    @Scheduled(fixedRate = 300_000) // 5 * 60 * 1000
    @SchedulerLock(
            name = "post.feedScore",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT1M"
    )
    public void rebuildFeedPool() {
        log.info("Starting feed score rebuild job...");

        long startTime = System.currentTimeMillis();

        try {
            feedService.rebuildRecommendPool();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Feed pool rebuild completed, duration={}ms", duration);

        } catch (Exception e) {
            log.error("Feed pool rebuild failed", e);
        }
    }
}
