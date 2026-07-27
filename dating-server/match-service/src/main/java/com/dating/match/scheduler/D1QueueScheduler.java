package com.dating.match.scheduler;

import com.dating.match.constant.MatchRedisKey;
import com.dating.match.mapper.D1UserMapper;
import com.dating.match.service.D1Generator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * D1 日更队列生成(7.5).
 *
 * <p>cron: {@code 0 0 7 * * *} UTC — 对应美东 EDT 02:00 / EST 03:00(冬令时准确、夏令时早 1h,trade-off).
 *
 * <p>分布式锁:Redisson {@code lock:match:d1:&lt;yyyymmdd&gt;},多实例只跑一份.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class D1QueueScheduler {

    private final D1UserMapper d1UserMapper;
    private final D1Generator d1Generator;
    private final RedissonClient redissonClient;

    private static final int BATCH_SIZE = 500;

    @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
    public void runDailyQueueGen() {
        Instant now = Instant.now();
        Instant yesterdayStart = now.minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant yesterdayEnd = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);

        String date = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String lockKey = MatchRedisKey.lockD1(date);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 60 * 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("D1 lock interrupted");
            return;
        }
        if (!acquired) {
            log.info("D1 already running on another instance, skip");
            return;
        }
        try {
            log.info("D1 cron start: yesterday={} ~ {}", yesterdayStart, yesterdayEnd);
            int totalUsers = 0;
            int totalCards = 0;

            List<Long> userIds;
            int offset = 0;
            do {
                userIds = d1UserMapper.listUsersWithSwipeInRange(
                        yesterdayStart, yesterdayEnd, BATCH_SIZE);
                if (userIds.isEmpty()) break;

                for (Long userId : userIds) {
                    try {
                        int written = d1Generator.generateForUser(userId);
                        totalCards += written;
                        totalUsers++;
                    } catch (Exception e) {
                        log.error("D1 generate failed userId={} err={}", userId, e.getMessage());
                    }
                }
                offset += userIds.size();
            } while (userIds.size() == BATCH_SIZE);

            log.info("D1 cron done: users={} cards={}", totalUsers, totalCards);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}