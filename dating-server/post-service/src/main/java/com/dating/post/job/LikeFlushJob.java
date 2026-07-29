package com.dating.post.job;

import com.dating.post.constant.RedisKey;
import com.dating.post.manager.PostStatManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 每分钟把点赞 Redis 增量写回 post_stats.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeFlushJob {

    private static final int BATCH_SIZE = 100;
    private final StringRedisTemplate stringRedisTemplate;
    private final PostStatManager postStatManager;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(
            name = "post.likeFlush",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S"
    )
    public void flushLikes() {
        int totalProcessed = 0;
        while (true) {
            Set<String> postIds = stringRedisTemplate.opsForSet()
                    .distinctRandomMembers(RedisKey.likeUpdatedSet(), BATCH_SIZE);
            if (postIds == null || postIds.isEmpty()) {
                break;
            }

            for (String postIdString : postIds) {
                long postId = Long.parseLong(postIdString);
                int delta = atomicGetAndReset(postId);
                try {
                    if (delta != 0) {
                        postStatManager.incrementLikeCount(postId, delta);
                        totalProcessed++;
                    }
                    removeDirtyMarkWhenNoNewDelta(postId);
                } catch (Exception e) {
                    if (delta != 0) {
                        stringRedisTemplate.opsForValue()
                                .increment(RedisKey.likeIncr(postId), delta);
                    }
                    stringRedisTemplate.opsForSet().add(
                            RedisKey.likeUpdatedSet(), postIdString);
                    log.error("Failed to flush like delta: postId={} delta={}",
                            postId, delta, e);
                }
            }
            if (postIds.size() < BATCH_SIZE) {
                break;
            }
        }
        log.info("Like flush completed: processed={}", totalProcessed);
    }

    private int atomicGetAndReset(long postId) {
        String script = "local v = redis.call('GET', KEYS[1]); " +
                "if not v then return 0 end; " +
                "redis.call('SET', KEYS[1], 0); return tonumber(v);";
        Long result = stringRedisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                List.of(RedisKey.likeIncr(postId)));
        return result == null ? 0 : result.intValue();
    }

    private void removeDirtyMarkWhenNoNewDelta(long postId) {
        String script = "local v = redis.call('GET', KEYS[1]); " +
                "if (not v) or tonumber(v) == 0 then " +
                "return redis.call('SREM', KEYS[2], ARGV[1]); end; return 0;";
        stringRedisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                List.of(RedisKey.likeIncr(postId), RedisKey.likeUpdatedSet()),
                String.valueOf(postId));
    }
}
