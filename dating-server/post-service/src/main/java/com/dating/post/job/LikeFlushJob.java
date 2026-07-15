package com.dating.post.job;

import com.dating.post.constant.RedisKey;
import com.dating.post.manager.PostStatManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * LikeFlushJob.
 *
 * <p>每分钟刷盘点赞计数.
 *
 * <p>核心流程:
 * 1. SRANDMEMBER updated_set 100
 * 2. Lua 原子 GET + SET 0
 * 3. UPDATE post_stats += delta
 * 4. SREM updated_set
 *
 * <p>ShedLock 多实例互斥: lockAtMostFor="PT2M", lockAtLeastFor="PT5S"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeFlushJob {

    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final PostStatManager postStatManager;

    /**
     * 点赞刷盘任务.
     *
     * <p>每 60 秒执行一次.
     */
    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(
            name = "post.likeFlush",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void flushLikes() {
        log.info("Starting like flush job...");

        int totalProcessed = 0;

        while (true) {
            // 1. 随机获取 100 个待刷盘的 post_id
            Set<String> postIds = stringRedisTemplate.opsForSet()
                    .distinctRandomMembers(RedisKey.updatedSet(), BATCH_SIZE);

            if (postIds == null || postIds.isEmpty()) {
                break;
            }

            for (String postIdStr : postIds) {
                try {
                    Long postId = Long.parseLong(postIdStr);

                    // 2. Lua 原子 GET + SET 0
                    int delta = atomicGetAndReset(postId);

                    if (delta != 0) {
                        // 3. UPDATE post_stats += delta
                        postStatManager.incrementLikeCount(postId, delta);
                        totalProcessed++;
                    }

                    // 4. SREM updated_set
                    stringRedisTemplate.opsForSet().remove(RedisKey.updatedSet(), postIdStr);

                } catch (Exception e) {
                    log.error("Failed to flush like for postId={}", postIdStr, e);
                }
            }

            // 如果获取的数量小于批次大小,说明已经处理完了
            if (postIds.size() < BATCH_SIZE) {
                break;
            }
        }

        log.info("Like flush completed, processed={}", totalProcessed);
    }

    /**
     * Lua 脚本:原子获取增量并归零.
     *
     * @param postId 帖子ID
     * @return 增量值
     */
    private int atomicGetAndReset(Long postId) {
        String key = RedisKey.likeIncr(postId);
        String value = stringRedisTemplate.opsForValue().get(key);

        if (value == null) {
            return 0;
        }

        int delta = Integer.parseInt(value);

        // Lua 脚本保证原子性
        String luaScript =
                "local v = redis.call('GET', KEYS[1]); " +
                "redis.call('SET', KEYS[1], 0); " +
                "return v;";

        Long result = stringRedisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, Long.class),
                List.of(key)
        );

        return result != null ? result.intValue() : 0;
    }
}
