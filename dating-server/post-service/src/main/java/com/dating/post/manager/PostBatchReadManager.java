package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostImageEntity;
import com.dating.post.entity.PostStatEntity;
import com.dating.post.mapper.PostImageMapper;
import com.dating.post.mapper.PostStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子列表/Feed 批量读取.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostBatchReadManager {

    private final PostImageMapper postImageMapper;
    private final PostStatMapper postStatMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public Map<Long, List<String>> listImageKeys(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<PostImageEntity> images = postImageMapper.selectList(
                new LambdaQueryWrapper<PostImageEntity>()
                        .in(PostImageEntity::getPostId, postIds)
                        .orderByAsc(PostImageEntity::getPostId)
                        .orderByAsc(PostImageEntity::getSortOrder));
        Map<Long, List<String>> result = new HashMap<>();
        for (PostImageEntity image : images) {
            result.computeIfAbsent(image.getPostId(), ignored -> new ArrayList<>())
                    .add(image.getImageKey());
        }
        return result;
    }

    public Map<Long, int[]> getCounts(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, int[]> result = new HashMap<>();
        for (PostStatEntity stat : postStatMapper.selectBatchIds(postIds)) {
            result.put(stat.getPostId(),
                    new int[]{stat.getLikeCount(), stat.getCommentCount()});
        }

        try {
            List<String> values = stringRedisTemplate.opsForValue().multiGet(
                    postIds.stream().map(RedisKey::likeIncr).toList());
            for (int i = 0; i < postIds.size(); i++) {
                int[] counts = result.computeIfAbsent(
                        postIds.get(i), ignored -> new int[]{0, 0});
                if (values != null && values.get(i) != null) {
                    counts[0] += Integer.parseInt(values.get(i));
                }
            }
        } catch (Exception e) {
            log.warn("Batch read like increments failed, use DB base counts: error={}",
                    e.getMessage());
        }
        return result;
    }
}
