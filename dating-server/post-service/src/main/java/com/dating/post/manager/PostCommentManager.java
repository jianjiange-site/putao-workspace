package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.post.config.SnowflakeIdConfig;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostCommentEntity;
import com.dating.post.exception.CommentNotFoundException;
import com.dating.post.mapper.PostCommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 评论 DB CRUD 和一级评论 Redis 窗口.
 */
@Component
@RequiredArgsConstructor
public class PostCommentManager {

    private static final int MAX_COMMENT_WINDOW = 200;

    private final PostCommentMapper commentMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SnowflakeIdConfig.SnowflakeIdGenerator snowflakeIdGenerator;

    public PostCommentEntity createComment(Long postId, Long userId, String content,
                                           Long rootId, Long parentId,
                                           Long replyToUserId) {
        PostCommentEntity comment = new PostCommentEntity();
        comment.setCommentId(snowflakeIdGenerator.nextId());
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setRootId(rootId);
        comment.setParentId(parentId);
        comment.setReplyToUserId(replyToUserId);
        comment.setStatus(1);
        comment.setDeleted(0);
        comment.setCreatedAt(Instant.now());
        commentMapper.insert(comment);
        return comment;
    }

    /**
     * 只缓存一级评论，保证 Redis 热路与 DB 冷路口径一致.
     */
    public void cacheRootComment(PostCommentEntity comment) {
        if (comment.getRootId() != 0L) {
            return;
        }
        String key = RedisKey.commentsZSet(comment.getPostId());
        stringRedisTemplate.opsForZSet().add(
                key, String.valueOf(comment.getCommentId()), comment.getCommentId());
        Long size = stringRedisTemplate.opsForZSet().size(key);
        if (size != null && size > MAX_COMMENT_WINDOW) {
            stringRedisTemplate.opsForZSet().removeRange(
                    key, 0, size - MAX_COMMENT_WINDOW - 1);
        }
        stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
    }

    public List<PostCommentEntity> listComments(Long postId, Long cursor, int limit) {
        String key = RedisKey.commentsZSet(postId);
        Set<String> ids;
        if (cursor == null || cursor <= 0) {
            ids = stringRedisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        } else {
            ids = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScore(key, 0, cursor - 1, 0, limit);
        }

        if (ids != null && ids.size() >= limit) {
            return getRootCommentsByIds(ids.stream().map(Long::parseLong).toList());
        }
        return listCommentsFromDb(postId, cursor, limit);
    }

    private List<PostCommentEntity> listCommentsFromDb(
            Long postId, Long cursor, int limit) {
        LambdaQueryWrapper<PostCommentEntity> wrapper =
                new LambdaQueryWrapper<PostCommentEntity>()
                        .eq(PostCommentEntity::getPostId, postId)
                        .eq(PostCommentEntity::getRootId, 0)
                        .eq(PostCommentEntity::getDeleted, 0)
                        .orderByDesc(PostCommentEntity::getCommentId)
                        .last("LIMIT " + limit);
        if (cursor != null && cursor > 0) {
            wrapper.lt(PostCommentEntity::getCommentId, cursor);
        }
        return commentMapper.selectList(wrapper);
    }

    private List<PostCommentEntity> getRootCommentsByIds(List<Long> commentIds) {
        if (commentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<PostCommentEntity> rows = commentMapper.selectList(
                new LambdaQueryWrapper<PostCommentEntity>()
                        .in(PostCommentEntity::getCommentId, commentIds)
                        .eq(PostCommentEntity::getRootId, 0)
                        .eq(PostCommentEntity::getDeleted, 0));
        Map<Long, PostCommentEntity> byId = new HashMap<>();
        for (PostCommentEntity row : rows) {
            byId.put(row.getCommentId(), row);
        }
        return commentIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    public PostCommentEntity getByCommentId(Long commentId) {
        PostCommentEntity comment = commentMapper.selectOne(
                new LambdaQueryWrapper<PostCommentEntity>()
                        .eq(PostCommentEntity::getCommentId, commentId)
                        .eq(PostCommentEntity::getDeleted, 0));
        if (comment == null) {
            throw new CommentNotFoundException(commentId);
        }
        return comment;
    }

    public void deleteComment(Long commentId) {
        commentMapper.update(null,
                new LambdaUpdateWrapper<PostCommentEntity>()
                        .eq(PostCommentEntity::getCommentId, commentId)
                        .eq(PostCommentEntity::getDeleted, 0)
                        .set(PostCommentEntity::getDeleted, 1));
    }

    public void evictComment(Long postId, Long commentId) {
        stringRedisTemplate.opsForZSet().remove(
                RedisKey.commentsZSet(postId), String.valueOf(commentId));
    }
}
