package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.post.config.SnowflakeIdConfig;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostCommentEntity;
import com.dating.post.exception.CommentNotFoundException;
import com.dating.post.mapper.PostCommentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * PostComment Manager.
 *
 * <p>负责评论的 CRUD 和 Redis ZSet 窗口操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostCommentManager {

    private static final int MAX_COMMENT_WINDOW = 200;

    private final PostCommentMapper commentMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SnowflakeIdConfig.SnowflakeIdGenerator snowflakeIdGenerator;
    private final PostStatManager postStatManager;

    /**
     * 创建评论.
     *
     * @param postId 帖子ID
     * @param userId 用户ID
     * @param content 内容
     * @param rootId 根评论ID(自身是根则为0)
     * @param parentId 直接父评论ID
     * @return 评论ID
     */
    public long createComment(Long postId, Long userId, String content,
                              Long rootId, Long parentId) {
        long commentId = snowflakeIdGenerator.nextId();

        PostCommentEntity comment = new PostCommentEntity();
        comment.setCommentId(commentId);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setRootId(rootId != null ? rootId : 0L);
        comment.setParentId(parentId != null ? parentId : 0L);
        comment.setReplyToUserId(0L);
        comment.setStatus(1);
        comment.setDeleted(0);
        comment.setCreatedAt(Instant.now());

        commentMapper.insert(comment);

        // 更新 Redis ZSet
        addToRedisZSet(postId, commentId);

        // 更新 Redis 增量
        postStatManager.incrRedisComment(postId, 1);
        // 标记待刷盘
        stringRedisTemplate.opsForSet().add(RedisKey.updatedSet(), String.valueOf(postId));

        log.debug("Comment created: commentId={} postId={} userId={}", commentId, postId, userId);
        return commentId;
    }

    /**
     * 添加评论到 Redis ZSet.
     */
    private void addToRedisZSet(Long postId, long commentId) {
        String key = RedisKey.commentsZSet(postId);
        // score 用 commentId 作为分数(实现按 ID 倒序)
        stringRedisTemplate.opsForZSet().add(key, String.valueOf(commentId), commentId);
        // 裁剪到 200 条
        Long size = stringRedisTemplate.opsForZSet().size(key);
        if (size != null && size > MAX_COMMENT_WINDOW) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, size - MAX_COMMENT_WINDOW - 1);
        }
        stringRedisTemplate.expire(key, 7, TimeUnit.DAYS);
    }

    /**
     * 查询评论列表(游标分页).
     *
     * @param postId 帖子ID
     * @param cursor 游标(上一页最末的comment_id)
     * @param pageSize 每页大小
     * @return 评论列表
     */
    public List<PostCommentEntity> listComments(Long postId, Long cursor, int pageSize) {
        // 1. 先尝试从 Redis ZSet 获取
        String key = RedisKey.commentsZSet(postId);

        if (cursor == null || cursor <= 0) {
            // 首次查询: 从 ZSet 取最新
            Set<String> commentIds = stringRedisTemplate.opsForZSet()
                    .reverseRange(key, 0, pageSize - 1);
            if (commentIds != null && !commentIds.isEmpty()) {
                return getCommentsByIds(parseIds(commentIds));
            }
        } else {
            // 游标查询: 取比 cursor 小的
            Set<String> commentIds = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScore(key, 0, cursor - 1, 0L, pageSize);
            if (commentIds != null && !commentIds.isEmpty()) {
                return getCommentsByIds(parseIds(commentIds));
            }
        }

        // 2. Redis ZSet 为空(冷帖或翻到200之外),回源 DB
        return listCommentsFromDb(postId, cursor, pageSize);
    }

    /**
     * 从数据库查询评论列表.
     */
    private List<PostCommentEntity> listCommentsFromDb(Long postId, Long cursor, int pageSize) {
        LambdaQueryWrapper<PostCommentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostCommentEntity::getPostId, postId)
                .eq(PostCommentEntity::getRootId, 0) // 只查一级评论
                .eq(PostCommentEntity::getDeleted, 0)
                .orderByDesc(PostCommentEntity::getCreatedAt)
                .last("LIMIT " + (pageSize + 1));

        if (cursor != null && cursor > 0) {
            wrapper.lt(PostCommentEntity::getCommentId, cursor);
        }

        return commentMapper.selectList(wrapper);
    }

    /**
     * 根据 commentId 批量查询评论.
     */
    private List<PostCommentEntity> getCommentsByIds(List<Long> commentIds) {
        if (commentIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<PostCommentEntity> comments = commentMapper.selectList(
                new LambdaQueryWrapper<PostCommentEntity>().in(PostCommentEntity::getCommentId, commentIds));
        // 按 commentId 倒序
        comments.sort((a, b) -> Long.compare(b.getCommentId(), a.getCommentId()));
        return comments;
    }

    /**
     * 解析字符串ID列表.
     */
    private List<Long> parseIds(Set<String> ids) {
        return ids.stream()
                .map(Long::parseLong)
                .toList();
    }

    /**
     * 根据 commentId 查询评论.
     *
     * @param commentId 评论ID
     * @return 评论实体
     * @throws CommentNotFoundException 评论不存在
     */
    public PostCommentEntity getByCommentId(Long commentId) {
        PostCommentEntity comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeleted() != 0) {
            throw new CommentNotFoundException(commentId);
        }
        return comment;
    }

    /**
     * 逻辑删除评论.
     *
     * @param commentId 评论ID
     * @param postId 帖子ID
     */
    public void deleteComment(Long commentId, Long postId) {
        LambdaUpdateWrapper<PostCommentEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PostCommentEntity::getCommentId, commentId)
                .set(PostCommentEntity::getDeleted, 1);

        commentMapper.update(null, wrapper);

        // 从 Redis ZSet 删除
        String key = RedisKey.commentsZSet(postId);
        stringRedisTemplate.opsForZSet().remove(key, String.valueOf(commentId));

        // 更新 Redis 增量
        postStatManager.incrRedisComment(postId, -1);
        // 标记待刷盘
        stringRedisTemplate.opsForSet().add(RedisKey.updatedSet(), String.valueOf(postId));

        log.debug("Comment deleted: commentId={} postId={}", commentId, postId);
    }
}
