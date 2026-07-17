package com.dating.gateway.service.impl;

import com.dating.gateway.client.PostClient;
import com.dating.gateway.dto.CreateCommentReq;
import com.dating.gateway.dto.CreatePostReq;
import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.PostService;
import com.dating.gateway.vo.CommentVO;
import com.dating.gateway.vo.PostDetailVO;
import com.dating.post.proto.PostDetailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PostServiceImpl implements PostService {

    private static final Logger log = LoggerFactory.getLogger(PostServiceImpl.class);

    private final PostClient postClient;

    public PostServiceImpl(PostClient postClient) {
        this.postClient = postClient;
    }

    @Override
    public Long createPost(Long userId, CreatePostReq req) {
        try {
            return postClient.createPost(req.getContent(), req.getImageKeys()).getPostId();
        } catch (Exception e) {
            log.error("Failed to create post", e);
            throw new GatewayException(10901, "Failed to create post");
        }
    }

    @Override
    public PostDetailVO getPostDetail(Long postId) {
        try {
            PostDetailResponse resp = postClient.getPostDetail(postId);
            return toPostDetailVO(resp);
        } catch (Exception e) {
            log.error("Failed to get post detail", e);
            throw new GatewayException(10901, "Failed to get post detail");
        }
    }

    @Override
    public boolean deletePost(Long userId, Long postId) {
        try {
            return postClient.deletePost(postId).getSuccess();
        } catch (Exception e) {
            log.error("Failed to delete post", e);
            throw new GatewayException(10901, "Failed to delete post");
        }
    }

    @Override
    public boolean likePost(Long userId, Long postId) {
        try {
            return postClient.actionLike(postId, true).getSuccess();
        } catch (Exception e) {
            log.error("Failed to like post", e);
            throw new GatewayException(10901, "Failed to like post");
        }
    }

    @Override
    public boolean unlikePost(Long userId, Long postId) {
        try {
            return postClient.actionLike(postId, false).getSuccess();
        } catch (Exception e) {
            log.error("Failed to unlike post", e);
            throw new GatewayException(10901, "Failed to unlike post");
        }
    }

    @Override
    public Long createComment(Long userId, Long postId, CreateCommentReq req) {
        try {
            return postClient.createComment(postId, req.getContent(), 0L, 0L).getCommentId();
        } catch (Exception e) {
            log.error("Failed to create comment", e);
            throw new GatewayException(10901, "Failed to create comment");
        }
    }

    @Override
    public boolean deleteComment(Long userId, Long commentId) {
        try {
            return postClient.deleteComment(commentId).getSuccess();
        } catch (Exception e) {
            log.error("Failed to delete comment", e);
            throw new GatewayException(10901, "Failed to delete comment");
        }
    }

    @Override
    public List<CommentVO> getComments(Long postId, int pageSize, long cursor) {
        try {
            var resp = postClient.listComments(postId, pageSize, cursor);
            List<CommentVO> result = new ArrayList<>();
            for (var c : resp.getCommentsList()) {
                CommentVO vo = new CommentVO();
                vo.setCommentId(c.getCommentId());
                vo.setPostId(c.getPostId());
                vo.setUserId(c.getUserId());
                vo.setContent(c.getContent());
                vo.setCreatedAtSeconds(c.getCreatedAt());
                result.add(vo);
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get comments", e);
            throw new GatewayException(10901, "Failed to get comments");
        }
    }

    @Override
    public List<PostDetailVO> getUserPosts(Long userId, int pageSize, long cursor) {
        try {
            var resp = postClient.listUserPosts(userId, pageSize, cursor);
            List<PostDetailVO> result = new ArrayList<>();
            for (var item : resp.getItemsList()) {
                result.add(toPostDetailVO(item));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get user posts", e);
            throw new GatewayException(10901, "Failed to get user posts");
        }
    }

    @Override
    public List<PostDetailVO> getFeed(Long viewerId, int pageSize, String cursor) {
        try {
            var resp = postClient.getRecommendFeed(pageSize, cursor);
            List<PostDetailVO> result = new ArrayList<>();
            for (var item : resp.getItemsList()) {
                result.add(toPostDetailVO(item));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get feed", e);
            throw new GatewayException(10901, "Failed to get feed");
        }
    }

    private PostDetailVO toPostDetailVO(PostDetailResponse resp) {
        PostDetailVO vo = new PostDetailVO();
        vo.setPostId(resp.getPostId());
        vo.setUserId(resp.getUserId());
        vo.setContent(resp.getContent());
        vo.setImageKeys(resp.getImageKeysList());
        vo.setLikeCount(resp.getLikeCount());
        vo.setCommentCount(resp.getCommentCount());
        vo.setLiked(resp.getIsLiked());
        vo.setCreatedAtSeconds(resp.getCreatedAt());
        return vo;
    }
}
