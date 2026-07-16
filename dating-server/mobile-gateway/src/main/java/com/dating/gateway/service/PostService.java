package com.dating.gateway.service;

import com.dating.gateway.dto.CreateCommentReq;
import com.dating.gateway.dto.CreatePostReq;
import com.dating.gateway.vo.CommentVO;
import com.dating.gateway.vo.PostDetailVO;

import java.util.List;

public interface PostService {
    Long createPost(Long userId, CreatePostReq req);
    PostDetailVO getPostDetail(Long postId);
    boolean deletePost(Long userId, Long postId);
    boolean likePost(Long userId, Long postId);
    boolean unlikePost(Long userId, Long postId);
    Long createComment(Long userId, Long postId, CreateCommentReq req);
    boolean deleteComment(Long userId, Long commentId);
    List<CommentVO> getComments(Long postId, int pageSize, long cursor);
    List<PostDetailVO> getUserPosts(Long userId, int pageSize, long cursor);
    List<PostDetailVO> getFeed(Long viewerId, int pageSize, String cursor);
}
