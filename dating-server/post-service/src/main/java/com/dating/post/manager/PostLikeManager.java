package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.LikeStatus;
import com.dating.post.entity.PostLikeEntity;
import com.dating.post.mapper.PostLikeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 点赞关系和计数增量.
 */
@Component
@RequiredArgsConstructor
public class PostLikeManager {

    private final PostLikeMapper postLikeMapper;
    private final PostStatManager postStatManager;

    public boolean upsertLike(Long userId, Long postId, boolean like) {
        int targetStatus = like ? LikeStatus.LIKED : LikeStatus.UNLIKED;
        int affected = postLikeMapper.upsertLike(userId, postId, targetStatus);
        if (affected <= 0) {
            return false;
        }
        postStatManager.incrRedisLike(postId, like ? 1 : -1);
        return true;
    }

    private PostLikeEntity findByUserIdAndPostId(Long userId, Long postId) {
        return postLikeMapper.selectOne(
                new LambdaQueryWrapper<PostLikeEntity>()
                        .eq(PostLikeEntity::getUserId, userId)
                        .eq(PostLikeEntity::getPostId, postId));
    }

    public boolean isLiked(Long userId, Long postId) {
        PostLikeEntity existing = findByUserIdAndPostId(userId, postId);
        return existing != null && existing.getStatus().equals(LikeStatus.LIKED);
    }

    public Map<Long, Boolean> batchIsLiked(Long userId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<PostLikeEntity> liked = postLikeMapper.selectList(
                new LambdaQueryWrapper<PostLikeEntity>()
                        .eq(PostLikeEntity::getUserId, userId)
                        .in(PostLikeEntity::getPostId, postIds)
                        .eq(PostLikeEntity::getStatus, LikeStatus.LIKED));
        Set<Long> likedIds = new HashSet<>();
        for (PostLikeEntity entity : liked) {
            likedIds.add(entity.getPostId());
        }
        Map<Long, Boolean> result = new HashMap<>();
        for (Long postId : postIds) {
            result.put(postId, likedIds.contains(postId));
        }
        return result;
    }
}
