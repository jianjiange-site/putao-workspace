package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostStatEntity;
import com.dating.post.mapper.PostStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 帖子统计底座 + Redis 点赞增量.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostStatManager {

    private final PostStatMapper postStatMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public int[] getCounts(Long postId) {
        PostStatEntity stat = postStatMapper.selectById(postId);
        int baseLikes = stat != null ? stat.getLikeCount() : 0;
        int baseComments = stat != null ? stat.getCommentCount() : 0;
        try {
            return new int[]{
                    baseLikes + getRedisIncr(postId, "likes"),
                    baseComments
            };
        } catch (Exception e) {
            log.warn("Read like increment failed, use DB base: postId={} error={}",
                    postId, e.getMessage());
            return new int[]{baseLikes, baseComments};
        }
    }

    public Map<Long, int[]> batchGetBaseCounts(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return postStatMapper.selectList(
                        new LambdaQueryWrapper<PostStatEntity>()
                                .in(PostStatEntity::getPostId, postIds))
                .stream()
                .collect(Collectors.toMap(
                        PostStatEntity::getPostId,
                        stat -> new int[]{stat.getLikeCount(), stat.getCommentCount()}));
    }

    public void incrementLikeCount(Long postId, int delta) {
        if (delta != 0) {
            postStatMapper.incrementLikeCount(postId, delta);
        }
    }

    public void incrementCommentCount(Long postId, int delta) {
        if (delta != 0) {
            postStatMapper.incrementCommentCount(postId, delta);
        }
    }

    /**
     * INCRBY + EXPIRE + SADD dirty set 在 Redis 内原子执行.
     */
    public void incrRedisLike(Long postId, int delta) {
        String script = "redis.call('INCRBY', KEYS[1], ARGV[1]); " +
                "redis.call('EXPIRE', KEYS[1], ARGV[2]); " +
                "redis.call('SADD', KEYS[2], ARGV[3]); return 1;";
        stringRedisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                List.of(RedisKey.likeIncr(postId), RedisKey.likeUpdatedSet()),
                String.valueOf(delta),
                String.valueOf(7 * 24 * 60 * 60),
                String.valueOf(postId));
    }

    public int getRedisIncr(Long postId, String type) {
        if (!"likes".equals(type)) {
            return 0;
        }
        String value = stringRedisTemplate.opsForValue().get(RedisKey.likeIncr(postId));
        return value == null ? 0 : Integer.parseInt(value);
    }
}
