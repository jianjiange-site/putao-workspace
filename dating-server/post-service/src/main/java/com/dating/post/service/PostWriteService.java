package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.config.SnowflakeIdConfig;
import com.dating.post.constant.RedisKey;
import com.dating.post.manager.PostManager;
import com.dating.post.mq.producer.PostFanoutProducer;
import com.dating.post.vo.PostDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * @param userId 用户ID
     * @param content 内容(1-1024字符)
     * @param imageKeys 图片key列表(最多9张)
     * @return postId
     */
    @Transactional(rollbackFor = Exception.class)
    public long createPost(Long userId, String content, List<String> imageKeys) {
        // 1. 入口校验已在 GrpcService 层做了

        // 2. 生成雪花ID
        long postId = snowflakeIdGenerator.nextId();

        // 3. 插入 posts 表
        postManager.createPost(postId, userId, content);

        // 4. 插入 post_images 表
        postManager.saveImages(postId, imageKeys);

        // 5. 初始化 post_stats
        postManager.initStats(postId);

        // 6. 缓存帖子详情
        cachePostDetail(postId, userId, content, imageKeys);

        // 7. 加入冷启动池
        addToColdStartPool(postId, userId);

        // 8. 发 RocketMQ 消息做写扩散
        sendFanoutMessage(postId, userId);

        log.info("Post created: postId={} userId={} images={}", postId, userId,
                imageKeys != null ? imageKeys.size() : 0);
        return postId;
    }

    /**
     * 缓存帖子详情到 Redis.
     */
    private void cachePostDetail(Long postId, Long userId, String content, List<String> imageKeys) {
        String key = RedisKey.postDetail(postId);
        stringRedisTemplate.opsForHash().put(key, "userId", String.valueOf(userId));
        stringRedisTemplate.opsForHash().put(key, "content", content);
        stringRedisTemplate.opsForHash().put(key, "createdAt", String.valueOf(Instant.now().getEpochSecond()));
        if (imageKeys != null && !imageKeys.isEmpty()) {
            stringRedisTemplate.opsForHash().put(key, "imageKeys", String.join(",", imageKeys));
        }
        stringRedisTemplate.expire(key, Duration.ofDays(7));
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
