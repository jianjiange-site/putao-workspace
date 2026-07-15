package com.dating.post.service;

import com.dating.post.entity.PostCommentEntity;
import com.dating.post.exception.CommentNotFoundException;
import com.dating.post.exception.ForbiddenException;
import com.dating.post.manager.PostCommentManager;
import com.dating.post.manager.PostManager;
import com.dating.post.vo.CommentVO;
import com.dating.post.vo.CommentsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * CommentService.
 *
 * <p>负责评论的增删列表业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final PostCommentManager commentManager;
    private final PostManager postManager;

    /**
     * 创建评论.
     *
     * @param userId 用户ID
     * @param postId 帖子ID
     * @param content 内容(1-512字符)
     * @param rootId 根评论ID(自身是根则为0)
     * @param parentId 直接父评论ID
     * @return 评论ID
     */
    public long createComment(Long userId, Long postId, String content, Long rootId, Long parentId) {
        // 1. 校验帖子是否存在
        postManager.findByPostId(postId);

        // 2. 创建评论
        long commentId = commentManager.createComment(
                postId, userId, content,
                rootId != null ? rootId : 0L,
                parentId != null ? parentId : 0L
        );

        log.info("Comment created: commentId={} postId={} userId={}", commentId, postId, userId);
        return commentId;
    }

    /**
     * 获取评论列表(游标分页).
     *
     * @param postId 帖子ID
     * @param cursor 游标
     * @param pageSize 每页大小
     * @return 评论列表响应
     */
    public CommentsVO listComments(Long postId, Long cursor, int pageSize) {
        // 1. 查询评论列表
        List<PostCommentEntity> comments = commentManager.listComments(postId, cursor, pageSize);

        if (comments.isEmpty()) {
            return CommentsVO.builder()
                    .comments(new ArrayList<>())
                    .nextCursor(0L)
                    .hasMore(false)
                    .build();
        }

        // 2. 判断是否有更多
        boolean hasMore = comments.size() >= pageSize;
        long nextCursor = hasMore ? comments.get(comments.size() - 1).getCommentId() : 0L;

        // 3. 转换为 VO
        List<CommentVO> items = comments.stream()
                .limit(pageSize) // 确保不超过 pageSize
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

    /**
     * 删除评论.
     *
     * @param commentId 评论ID
     * @param userId 操作人ID(必须为评论作者)
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long userId) {
        // 1. 查询评论
        PostCommentEntity comment = commentManager.getByCommentId(commentId);

        // 2. 权限校验
        if (!comment.getUserId().equals(userId)) {
            throw new ForbiddenException("Only the author can delete the comment");
        }

        // 3. 逻辑删除
        commentManager.deleteComment(commentId, comment.getPostId());

        log.info("Comment deleted: commentId={} userId={}", commentId, userId);
    }
}
