package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.LikeStatus;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostLikeEntity;
import com.dating.post.mapper.PostLikeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PostLike Manager.
 *
 * <p>负责 post_like upsert 操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostLikeManager {

    private final PostLikeMapper postLikeMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final PostStatManager postStatManager;

    /**
     * 点赞/取消点赞(幂等 upsert).
     *
     * @param userId 用户ID
     * @param postId 帖子ID
     * @param like true=点赞, false=取消点赞
     * @return true=状态真变了, false=幂等(已是目标状态)
     */
    public boolean upsertLike(Long userId, Long postId, boolean like) {
        int targetStatus = like ? LikeStatus.LIKED : LikeStatus.UNLIKED;

        // 1. 先查询当前状态
        PostLikeEntity existing = findByUserIdAndPostId(userId, postId);

        if (existing != null && existing.getStatus().equals(targetStatus)) {
            // 已是目标状态,幂等返回
            log.debug("Like upsert idempotent: userId={} postId={} status={}",
                    userId, postId, targetStatus);
            return false;
        }

        // 2. 执行 upsert
        postLikeMapper.upsertLike(userId, postId, targetStatus);

        // 3. 如果是真变状态,更新 Redis 增量
        if (existing == null || !existing.getStatus().equals(targetStatus)) {
            int delta = like ? 1 : -1;
            postStatManager.incrRedisLike(postId, delta);
            // 标记待刷盘
            stringRedisTemplate.opsForSet().add(RedisKey.updatedSet(), String.valueOf(postId));
            log.debug("Like state changed: userId={} postId={} liked={}", userId, postId, like);
        }

        return true;
    }

    /**
     * 根据 userId 和 postId 查询.
     */
    private PostLikeEntity findByUserIdAndPostId(Long userId, Long postId) {
        LambdaQueryWrapper<PostLikeEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostLikeEntity::getUserId, userId)
                .eq(PostLikeEntity::getPostId, postId);
        return postLikeMapper.selectOne(wrapper);
    }

    /**
     * 查询用户是否点赞.
     *
     * @param userId 用户ID
     * @param postId 帖子ID
     * @return true=已点赞
     */
    public boolean isLiked(Long userId, Long postId) {
        PostLikeEntity existing = findByUserIdAndPostId(userId, postId);
        return existing != null && existing.getStatus().equals(LikeStatus.LIKED);
    }

    /**
     * 批量查询用户的点赞状态.
     *
     * @param userId 用户ID
     * @param postIds 帖子ID列表
     * @return postId -> isLiked 映射
     */
    public java.util.Map<Long, Boolean> batchIsLiked(Long userId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        LambdaQueryWrapper<PostLikeEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostLikeEntity::getUserId, userId)
                .in(PostLikeEntity::getPostId, postIds)
                .eq(PostLikeEntity::getStatus, LikeStatus.LIKED);

        List<PostLikeEntity> liked = postLikeMapper.selectList(wrapper);

        return postIds.stream()
                .collect(java.util.stream.Collectors.toMap(
                        postId -> postId,
                        postId -> liked.stream()
                                .anyMatch(l -> l.getPostId().equals(postId))
                ));
    }
}
