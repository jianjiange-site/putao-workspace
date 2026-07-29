package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.config.SnowflakeIdConfig;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostEntity;
import com.dating.post.exception.ForbiddenException;
import com.dating.post.manager.PostDetailCacheManager;
import com.dating.post.manager.PostFanoutOutboxManager;
import com.dating.post.manager.PostManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * 发帖和删帖业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostWriteService {

    private static final int COLD_START_POOL_SIZE = 10_000;

    private final PostManager postManager;
    private final SnowflakeIdConfig.SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserClient userClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final PostDetailCacheManager postDetailCacheManager;
    private final PostFanoutOutboxManager fanoutOutboxManager;

    /**
     * posts、post_images、post_stats、fanout_outbox 在同一本地事务内提交.
     * Redis 缓存和冷启动池只在事务提交后更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public long createPost(Long userId, String content, List<String> imageKeys) {
        long postId = snowflakeIdGenerator.nextId();
        List<String> safeImageKeys = imageKeys == null ? List.of() : List.copyOf(imageKeys);

        PostEntity post = postManager.createPost(postId, userId, content);
        postManager.saveImages(postId, safeImageKeys);
        postManager.initStats(postId);
        fanoutOutboxManager.enqueue(
                postId, userId, post.getCreatedAt().getEpochSecond());

        afterCommit(() -> {
            postDetailCacheManager.put(post, safeImageKeys);
            addToColdStartPool(postId, userId);
        });

        log.info("Post created: postId={} userId={} images={}",
                postId, userId, safeImageKeys.size());
        return postId;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId, Long userId) {
        PostEntity post = postManager.getByPostId(postId);
        if (!post.getUserId().equals(userId)) {
            throw new ForbiddenException("Only the author can delete the post");
        }

        postManager.deletePost(postId);
        afterCommit(() -> {
            postDetailCacheManager.evict(postId);
            removeFromColdStartPools(postId);
        });
        log.info("Post deleted: postId={} userId={}", postId, userId);
    }

    private void addToColdStartPool(Long postId, Long userId) {
        try {
            boolean isMale = userClient.isMale(userId);
            String key = isMale
                    ? RedisKey.coldStartPoolMale()
                    : RedisKey.coldStartPoolFemale();
            stringRedisTemplate.opsForZSet().add(
                    key, String.valueOf(postId), System.currentTimeMillis() / 1000.0);
            Long size = stringRedisTemplate.opsForZSet().size(key);
            if (size != null && size > COLD_START_POOL_SIZE) {
                stringRedisTemplate.opsForZSet().removeRange(
                        key, 0, size - COLD_START_POOL_SIZE - 1);
            }
            stringRedisTemplate.expire(key, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("Failed to add to cold start pool: postId={} error={}",
                    postId, e.getMessage());
        }
    }

    private void removeFromColdStartPools(Long postId) {
        try {
            stringRedisTemplate.opsForZSet().remove(
                    RedisKey.coldStartPoolMale(), String.valueOf(postId));
            stringRedisTemplate.opsForZSet().remove(
                    RedisKey.coldStartPoolFemale(), String.valueOf(postId));
        } catch (Exception e) {
            log.warn("Failed to remove from cold start pools: postId={} error={}",
                    postId, e.getMessage());
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }
}
