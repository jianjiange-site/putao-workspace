package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.config.SnowflakeIdConfig;
import com.dating.post.constant.RedisKey;
import com.dating.post.manager.PostManager;
import com.dating.post.mq.producer.PostFanoutProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PostWriteService.
 *
 * <p>负责发帖和删帖的业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostWriteService {

    private final PostManager postManager;
    private final SnowflakeIdConfig.SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserClient userClient;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    private final PostFanoutProducer postFanoutProducer;

    /**
     * 创建帖子.
     *
     * <p>事务边界只覆盖 DB 写入（posts、post_images、post_stats），
     * Redis 缓存、冷启动池、MQ 发送均在事务外独立执行，失败不影响发帖结果。
     *
     * @param userId 用户ID
     * @param content 内容(1-1024字符)
     * @param imageKeys 图片key列表(最多9张)
     * @return postId
     */
    public long createPost(Long userId, String content, List<String> imageKeys) {
        // 1. 生成雪花ID（不占事务）
        long postId = snowflakeIdGenerator.nextId();

        // 2. DB 写入（事务边界内）
        doCreatePost(postId, userId, content, imageKeys);

        // 3. 缓存帖子详情（事务外，失败不回滚）
        cachePostDetail(postId, userId, content, imageKeys);

        // 4. 加入冷启动池（事务外，失败不回滚）
        addToColdStartPool(postId, userId);

        // 5. 发 RocketMQ 消息做写扩散（事务外，失败不回滚）
        sendFanoutMessage(postId, userId);

        log.info("Post created: postId={} userId={} images={}", postId, userId,
                imageKeys != null ? imageKeys.size() : 0);
        return postId;
    }

    /**
     * DB 写入，事务边界.
     */
    @Transactional(rollbackFor = Exception.class)
    void doCreatePost(long postId, Long userId, String content, List<String> imageKeys) {
        postManager.createPost(postId, userId, content);
        postManager.saveImages(postId, imageKeys);
        postManager.initStats(postId);
    }

    /**
     * 缓存帖子详情到 Redis.
     */
    private void cachePostDetail(Long postId, Long userId, String content, List<String> imageKeys) {
        try {
            String key = RedisKey.postDetail(postId);
            stringRedisTemplate.opsForHash().put(key, "userId", String.valueOf(userId));
            stringRedisTemplate.opsForHash().put(key, "content", content);
            stringRedisTemplate.opsForHash().put(key, "createdAt", String.valueOf(Instant.now().getEpochSecond()));
            if (imageKeys != null && !imageKeys.isEmpty()) {
                stringRedisTemplate.opsForHash().put(key, "imageKeys", String.join(",", imageKeys));
            }
            stringRedisTemplate.expire(key, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("Failed to cache post detail: postId={} error={}", postId, e.getMessage());
        }
    }

    /**
     * 加入冷启动池.
     */
    private void addToColdStartPool(Long postId, Long userId) {
        try {
            boolean isMale = userClient.isMale(userId);
            String key = isMale ? RedisKey.coldStartPoolMale() : RedisKey.coldStartPoolFemale();
            long score = Instant.now().getEpochSecond();
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(postId), score);
            log.debug("Added to cold start pool: postId={} key={} score={}", postId, key, score);
        } catch (Exception e) {
            log.warn("Failed to add to cold start pool: postId={} error={}", postId, e.getMessage());
        }
    }

    /**
     * 发送写扩散消息.
     */
    private void sendFanoutMessage(Long postId, Long userId) {
        try {
            long createdAtEpoch = Instant.now().getEpochSecond();
            postFanoutProducer.send(postId, userId, createdAtEpoch);
        } catch (Exception e) {
            log.warn("Failed to send fanout message: postId={} error={}", postId, e.getMessage());
        }
    }

    /**
     * 删除帖子.
     *
     * @param postId 帖子ID
     * @param userId 操作人ID(必须为帖子作者)
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId, Long userId) {
        // 1. 查询校验
        var post = postManager.getByPostId(postId);

        // 2. 权限校验
        if (!post.getUserId().equals(userId)) {
            throw new com.dating.post.exception.ForbiddenException("Only the author can delete the post");
        }

        // 3. 逻辑删除
        postManager.deletePost(postId);

        // 4. 删除缓存
        postManager.deletePostCache(postId);

        // 5. 从冷启动池移除
        removeFromColdStartPools(postId);

        log.info("Post deleted: postId={} userId={}", postId, userId);
    }

    /**
     * 从冷启动池移除.
     */
    private void removeFromColdStartPools(Long postId) {
        try {
            stringRedisTemplate.opsForZSet().remove(RedisKey.coldStartPoolMale(), String.valueOf(postId));
            stringRedisTemplate.opsForZSet().remove(RedisKey.coldStartPoolFemale(), String.valueOf(postId));
        } catch (Exception e) {
            log.warn("Failed to remove from cold start pools: postId={} error={}", postId, e.getMessage());
        }
    }
}
