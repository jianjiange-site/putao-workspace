package com.dating.post.service;

import com.dating.post.entity.PostCommentEntity;
import com.dating.post.exception.ForbiddenException;
import com.dating.post.manager.PostCommentManager;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.vo.CommentVO;
import com.dating.post.vo.CommentsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论增删和一级评论列表.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final PostCommentManager commentManager;
    private final PostManager postManager;
    private final PostStatManager postStatManager;

    @Transactional(rollbackFor = Exception.class)
    public long createComment(Long userId, Long postId, String content,
                              Long rootId, Long parentId) {
        postManager.getByPostId(postId);

        long normalizedRootId = rootId == null ? 0L : rootId;
        long normalizedParentId = parentId == null ? 0L : parentId;
        long replyToUserId = 0L;

        if (normalizedRootId == 0L && normalizedParentId != 0L) {
            throw new IllegalArgumentException(
                    "parentId must be 0 for a root comment");
        }
        if (normalizedRootId != 0L) {
            PostCommentEntity root = commentManager.getByCommentId(normalizedRootId);
            if (!root.getPostId().equals(postId) || root.getRootId() != 0L) {
                throw new IllegalArgumentException("Invalid root comment");
            }
            PostCommentEntity parent = normalizedParentId == 0L
                    ? root
                    : commentManager.getByCommentId(normalizedParentId);
            boolean sameThread = parent.getCommentId().equals(normalizedRootId)
                    || parent.getRootId().equals(normalizedRootId);
            if (!parent.getPostId().equals(postId) || !sameThread) {
                throw new IllegalArgumentException("Invalid parent comment");
            }
            normalizedParentId = parent.getCommentId();
            replyToUserId = parent.getUserId();
        }

        PostCommentEntity comment = commentManager.createComment(
                postId, userId, content,
                normalizedRootId, normalizedParentId, replyToUserId);
        postStatManager.incrementCommentCount(postId, 1);
        afterCommit(() -> {
            try {
                commentManager.cacheRootComment(comment);
            } catch (Exception e) {
                log.warn("Cache root comment failed: commentId={} error={}",
                        comment.getCommentId(), e.getMessage());
            }
        });
        return comment.getCommentId();
    }

    public CommentsVO listComments(Long postId, Long cursor, int requestedPageSize) {
        int pageSize = Math.max(1, Math.min(requestedPageSize, MAX_PAGE_SIZE));
        List<PostCommentEntity> queried = commentManager.listComments(
                postId, cursor, pageSize + 1);
        if (queried.isEmpty()) {
            return CommentsVO.builder()
                    .comments(new ArrayList<>())
                    .nextCursor(0L)
                    .hasMore(false)
                    .build();
        }

        boolean hasMore = queried.size() > pageSize;
        List<PostCommentEntity> page = queried.stream().limit(pageSize).toList();
        long nextCursor = hasMore
                ? page.get(page.size() - 1).getCommentId()
                : 0L;
        List<CommentVO> items = page.stream()
                .map(c -> CommentVO.builder()
                        .commentId(c.getCommentId())
                        .postId(c.getPostId())
                        .userId(c.getUserId())
                        .rootId(c.getRootId())
                        .parentId(c.getParentId())
                        .replyToUserId(c.getReplyToUserId())
                        .content(c.getContent())
                        .createdAt(c.getCreatedAt().getEpochSecond())
                        .build())
                .toList();
        return CommentsVO.builder()
                .comments(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long userId) {
        PostCommentEntity comment = commentManager.getByCommentId(commentId);
        if (!comment.getUserId().equals(userId)) {
            throw new ForbiddenException("Only the author can delete the comment");
        }
        commentManager.deleteComment(commentId);
        postStatManager.incrementCommentCount(comment.getPostId(), -1);
        afterCommit(() -> {
            try {
                commentManager.evictComment(comment.getPostId(), commentId);
            } catch (Exception e) {
                log.warn("Evict comment cache failed: commentId={} error={}",
                        commentId, e.getMessage());
            }
        });
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }
}
