package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.entity.PostStatEntity;
import com.dating.post.mapper.PostStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PostStat Manager.
 *
 * <p>负责 post_stat 增量更新.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostStatManager {

    private final PostStatMapper postStatMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 获取帖子计数(含Redis增量).
     *
     * @param postId 帖子ID
     * @return [likeCount, commentCount]
     */
    public int[] getCounts(Long postId) {
        // 1. 从DB获取基准值
        PostStatEntity stat = postStatMapper.selectById(postId);
        int baseLikes = stat != null ? stat.getLikeCount() : 0;
        int baseComments = stat != null ? stat.getCommentCount() : 0;

        // 2. 从Redis获取增量
        String likeIncrKey = com.dating.post.constant.RedisKey.likeIncr(postId);
        String commentIncrKey = com.dating.post.constant.RedisKey.commentIncr(postId);

        String likeIncrStr = stringRedisTemplate.opsForValue().get(likeIncrKey);
        String commentIncrStr = stringRedisTemplate.opsForValue().get(commentIncrKey);

        int likeIncr = likeIncrStr != null ? Integer.parseInt(likeIncrStr) : 0;
        int commentIncr = commentIncrStr != null ? Integer.parseInt(commentIncrStr) : 0;

        return new int[]{baseLikes + likeIncr, baseComments + commentIncr};
    }

    /**
     * 获取基础点赞数(不含Redis增量).
     *
     * @param postId 帖子ID
     * @return 基础点赞数
     */
    public int getBaseLikeCount(Long postId) {
        PostStatEntity stat = postStatMapper.selectById(postId);
        return stat != null ? stat.getLikeCount() : 0;
    }

    /**
     * 获取基础评论数(不含Redis增量).
     *
     * @param postId 帖子ID
     * @return 基础评论数
     */
    public int getBaseCommentCount(Long postId) {
        PostStatEntity stat = postStatMapper.selectById(postId);
        return stat != null ? stat.getCommentCount() : 0;
    }

    /**
     * 批量获取基础计数.
     *
     * @param postIds postId列表
     * @return postId -> [likeCount, commentCount] 映射
     */
    public Map<Long, int[]> batchGetBaseCounts(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<PostStatEntity> stats = postStatMapper.selectList(
                new LambdaQueryWrapper<PostStatEntity>().in(PostStatEntity::getPostId, postIds));
        return stats.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PostStatEntity::getPostId,
                        stat -> new int[]{stat.getLikeCount(), stat.getCommentCount()}
                ));
    }

    /**
     * 增量更新点赞数(用于LikeFlushJob).
     *
     * @param postId 帖子ID
     * @param delta 增量(可为负数)
     */
    public void incrementLikeCount(Long postId, int delta) {
        if (delta == 0) {
            return;
        }

        LambdaQueryWrapper<PostStatEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostStatEntity::getPostId, postId);

        PostStatEntity update = new PostStatEntity();
        update.setLikeCount(delta); // MyBatis-Plus 的 update 方法会用表达式
        update.setUpdatedAt(Instant.now());

        // 使用原生SQL更新
        postStatMapper.update(null,
                new LambdaQueryWrapper<PostStatEntity>()
                        .eq(PostStatEntity::getPostId, postId)
                        .apply("like_count = like_count + " + delta));

        log.debug("Incremented like count: postId={} delta={}", postId, delta);
    }

    /**
     * 增量更新评论数(用于CommentFlushJob).
     *
     * @param postId 帖子ID
     * @param delta 增量(可为负数)
     */
    public void incrementCommentCount(Long postId, int delta) {
        if (delta == 0) {
            return;
        }

        postStatMapper.update(null,
                new LambdaQueryWrapper<PostStatEntity>()
                        .eq(PostStatEntity::getPostId, postId)
                        .apply("comment_count = comment_count + " + delta));

        log.debug("Incremented comment count: postId={} delta={}", postId, delta);
    }

    /**
     * Redis INCR 点赞增量.
     *
     * @param postId 帖子ID
     * @param delta 增量(+1/-1)
     */
    public void incrRedisLike(Long postId, int delta) {
        String key = com.dating.post.constant.RedisKey.likeIncr(postId);
        if (delta > 0) {
            stringRedisTemplate.opsForValue().increment(key, delta);
        } else {
            stringRedisTemplate.opsForValue().decrement(key, Math.abs(delta));
        }
        stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
    }

    /**
     * Redis INCR 评论增量.
     *
     * @param postId 帖子ID
     * @param delta 增量(+1/-1)
     */
    public void incrRedisComment(Long postId, int delta) {
        String key = com.dating.post.constant.RedisKey.commentIncr(postId);
        if (delta > 0) {
            stringRedisTemplate.opsForValue().increment(key, delta);
        } else {
            stringRedisTemplate.opsForValue().decrement(key, Math.abs(delta));
        }
        stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
    }

    /**
     * 获取Redis增量值.
     *
     * @param postId 帖子ID
     * @param type "likes" 或 "comments"
     * @return 增量值
     */
    public int getRedisIncr(Long postId, String type) {
        String key = type.equals("likes")
                ? com.dating.post.constant.RedisKey.likeIncr(postId)
                : com.dating.post.constant.RedisKey.commentIncr(postId);

        String value = stringRedisTemplate.opsForValue().get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }
}
