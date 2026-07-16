package com.dating.gateway.controller;

import com.dating.gateway.dto.CreateCommentReq;
import com.dating.gateway.dto.CreatePostReq;
import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.PostService;
import com.dating.gateway.vo.CommentVO;
import com.dating.gateway.vo.PostDetailVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Post", description = "Post APIs")
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @PostMapping
    @Operation(summary = "Create a post")
    public Result<Map<String, Long>> createPost(@Valid @RequestBody CreatePostReq req) {
        Long userId = RequestContext.current().getUserId();
        Long postId = postService.createPost(userId, req);
        return Result.ok(Map.of("postId", postId));
    }

    @GetMapping("/{postId}")
    @Operation(summary = "Get post detail")
    public Result<PostDetailVO> getPostDetail(@PathVariable Long postId) {
        return Result.ok(postService.getPostDetail(postId));
    }

    @DeleteMapping("/{postId}")
    @Operation(summary = "Delete a post")
    public Result<Void> deletePost(@PathVariable Long postId) {
        Long userId = RequestContext.current().getUserId();
        postService.deletePost(userId, postId);
        return Result.ok();
    }

    @PostMapping("/{postId}/like")
    @Operation(summary = "Like a post")
    public Result<Void> likePost(@PathVariable Long postId) {
        Long userId = RequestContext.current().getUserId();
        postService.likePost(userId, postId);
        return Result.ok();
    }

    @DeleteMapping("/{postId}/like")
    @Operation(summary = "Unlike a post")
    public Result<Void> unlikePost(@PathVariable Long postId) {
        Long userId = RequestContext.current().getUserId();
        postService.unlikePost(userId, postId);
        return Result.ok();
    }

    @PostMapping("/{postId}/comments")
    @Operation(summary = "Create a comment")
    public Result<Map<String, Long>> createComment(@PathVariable Long postId,
                                                   @Valid @RequestBody CreateCommentReq req) {
        Long userId = RequestContext.current().getUserId();
        Long commentId = postService.createComment(userId, postId, req);
        return Result.ok(Map.of("commentId", commentId));
    }

    @GetMapping("/{postId}/comments")
    @Operation(summary = "List comments")
    public Result<List<CommentVO>> getComments(@PathVariable Long postId,
                                               @RequestParam(defaultValue = "20") int pageSize,
                                               @RequestParam(defaultValue = "0") long cursor) {
        return Result.ok(postService.getComments(postId, pageSize, cursor));
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "Delete a comment")
    public Result<Void> deleteComment(@PathVariable Long commentId) {
        Long userId = RequestContext.current().getUserId();
        postService.deleteComment(userId, commentId);
        return Result.ok();
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Get user posts")
    public Result<List<PostDetailVO>> getUserPosts(@PathVariable Long userId,
                                                   @RequestParam(defaultValue = "20") int pageSize,
                                                   @RequestParam(defaultValue = "0") long cursor) {
        return Result.ok(postService.getUserPosts(userId, pageSize, cursor));
    }

    @GetMapping("/feed")
    @Operation(summary = "Get recommended feed")
    public Result<List<PostDetailVO>> getFeed(@RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) String cursor) {
        Long userId = RequestContext.current().getUserId();
        return Result.ok(postService.getFeed(userId, pageSize, cursor));
    }
}
