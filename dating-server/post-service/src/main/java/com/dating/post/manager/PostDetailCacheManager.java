package com.dating.post.manager;

import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 帖子公共详情两级缓存.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostDetailCacheManager {

    private static final Duration POSITIVE_TTL = Duration.ofDays(7);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);
    private static final long TTL_JITTER_SECONDS = 20 * 60L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<Long, CacheValue> postDetailLocalCache;

    /**
     * @return null=未命中; found=false=负缓存命中
     */
    public CacheValue get(Long postId) {
        CacheValue local = postDetailLocalCache.getIfPresent(postId);
        if (local != null) {
            return local;
        }
        try {
            String json = stringRedisTemplate.opsForValue()
                    .get(RedisKey.postDetail(postId));
            if (json == null || json.isBlank()) {
                return null;
            }
            CacheValue value = objectMapper.readValue(json, CacheValue.class);
            postDetailLocalCache.put(postId, value);
            return value;
        } catch (Exception e) {
            log.warn("Read post detail cache failed: postId={} error={}",
                    postId, e.getMessage());
            return null;
        }
    }

    public void put(PostEntity post, List<String> imageKeys) {
        CacheValue value = CacheValue.found(post, imageKeys);
        write(post.getPostId(), value, POSITIVE_TTL.plusSeconds(
                ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS + 1)));
    }

    public void putNotFound(Long postId) {
        write(postId, CacheValue.notFound(postId), NEGATIVE_TTL);
    }

    private void write(Long postId, CacheValue value, Duration ttl) {
        postDetailLocalCache.put(postId, value);
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKey.postDetail(postId),
                    objectMapper.writeValueAsString(value),
                    ttl);
        } catch (Exception e) {
            log.warn("Write post detail cache failed: postId={} error={}",
                    postId, e.getMessage());
        }
    }

    public void evict(Long postId) {
        evictLocal(postId);
        try {
            stringRedisTemplate.delete(RedisKey.postDetail(postId));
            stringRedisTemplate.convertAndSend(
                    RedisKey.postDetailEvictChannel(), String.valueOf(postId));
        } catch (Exception e) {
            log.warn("Evict post detail cache failed: postId={} error={}",
                    postId, e.getMessage());
        }
    }

    public void evictLocal(Long postId) {
        postDetailLocalCache.invalidate(postId);
    }

    public record CacheValue(
            boolean found,
            Long postId,
            Long userId,
            String content,
            Integer status,
            List<String> imageKeys,
            long createdAtEpoch
    ) {
        public static CacheValue found(PostEntity post, List<String> imageKeys) {
            return new CacheValue(
                    true,
                    post.getPostId(),
                    post.getUserId(),
                    post.getContent(),
                    post.getStatus(),
                    imageKeys == null ? List.of() : List.copyOf(imageKeys),
                    post.getCreatedAt().getEpochSecond());
        }

        public static CacheValue notFound(Long postId) {
            return new CacheValue(false, postId, 0L, "", 0, List.of(), 0L);
        }
    }
}
