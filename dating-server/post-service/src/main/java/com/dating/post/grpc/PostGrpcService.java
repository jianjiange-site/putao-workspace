package com.dating.post.grpc;

import com.dating.post.constant.ErrorCode;
import com.dating.post.proto.*;
import com.dating.post.service.*;
import com.dating.post.vo.CommentVO;
import com.dating.post.vo.CommentsVO;
import com.dating.post.vo.PostDetailVO;
import com.dating.post.vo.RecommendFeedVO;
import com.dating.post.vo.UserPostsVO;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Post gRPC Service 实现.
 *
 * <p>实现 9 个 RPC 接口,只编排 service.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class PostGrpcService extends PostServiceGrpc.PostServiceImplBase {

    private final PostWriteService postWriteService;
    private final PostReadService postReadService;
    private final LikeService likeService;
    private final CommentService commentService;
    private final FeedService feedService;

    @Override
    public void createPost(CreatePostRequest request, StreamObserver<CreatePostResponse> responseObserver) {
        Long userId = extractUserId(request);
        String content = request.getContent();
        var imageKeys = request.getImageKeysList();

        // 1. 入口校验
        if (content == null || content.trim().isEmpty()) {
            sendError(responseObserver, ErrorCode.CONTENT_EMPTY);
            return;
        }
        if (content.length() > 1024) {
            sendError(responseObserver, ErrorCode.CONTENT_TOO_LONG);
            return;
        }
        if (imageKeys != null && imageKeys.size() > 9) {
            sendError(responseObserver, ErrorCode.IMAGE_COUNT_EXCEEDED);
            return;
        }

        try {
            long postId = postWriteService.createPost(userId, content.trim(), imageKeys);

            CreatePostResponse response = CreatePostResponse.newBuilder()
                    .setPostId(postId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreatePost failed: userId={}", userId, e);
            sendError(responseObserver, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public void getPostDetail(GetPostDetailRequest request, StreamObserver<PostDetailResponse> responseObserver) {
        Long currentUserId = extractUserId(request);
        long postId = request.getPostId();

        try {
            PostDetailVO detail = postReadService.getPostDetail(postId, currentUserId);

            PostDetailResponse response = PostDetailResponse.newBuilder()
                    .setPostId(detail.getPostId())
                    .setUserId(detail.getUserId())
                    .setContent(detail.getContent())
                    .addAllImageKeys(detail.getImageKeys())
                    .setLikeCount(detail.getLikeCount())
                    .setCommentCount(detail.getCommentCount())
                    .setIsLiked(detail.getIsLiked())
                    .setCreatedAt(detail.getCreatedAt())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetPostDetail failed: postId={}", postId, e);
            responseObserver.onError(io.grpc.Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listUserPosts(ListUserPostsRequest request, StreamObserver<ListUserPostsResponse> responseObserver) {
        Long currentUserId = extractUserId(request);
        long userId = request.getUserId();
        int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
        long cursor = request.getCursor();

        try {
            UserPostsVO result = postReadService.listUserPosts(userId, currentUserId, cursor, pageSize);

            ListUserPostsResponse.Builder builder = ListUserPostsResponse.newBuilder()
                    .setNextCursor(result.getNextCursor())
                    .setHasMore(result.getHasMore());

            for (PostDetailVO item : result.getItems()) {
                builder.addItems(PostDetailResponse.newBuilder()
                        .setPostId(item.getPostId())
                        .setUserId(item.getUserId())
                        .setContent(item.getContent())
                        .addAllImageKeys(item.getImageKeys())
                        .setLikeCount(item.getLikeCount())
                        .setCommentCount(item.getCommentCount())
                        .setIsLiked(item.getIsLiked())
                        .setCreatedAt(item.getCreatedAt())
                        .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListUserPosts failed: userId={}", userId, e);
            sendError(responseObserver, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public void actionLike(ActionLikeRequest request, StreamObserver<ActionLikeResponse> responseObserver) {
        Long userId = extractUserId(request);
        long postId = request.getPostId();
        boolean like = request.getAction() == LikeAction.LIKE;

        try {
            likeService.actionLike(userId, postId, like);

            ActionLikeResponse response = ActionLikeResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ActionLike failed: userId={} postId={}", userId, postId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void createComment(CreateCommentRequest request, StreamObserver<CreateCommentResponse> responseObserver) {
        Long userId = extractUserId(request);
        long postId = request.getPostId();
        String content = request.getContent();
        long rootId = request.getRootId();
        long parentId = request.getParentId();

        // 入口校验
        if (content == null || content.trim().isEmpty()) {
            sendError(responseObserver, ErrorCode.COMMENT_CONTENT_EMPTY);
            return;
        }
        if (content.length() > 512) {
            sendError(responseObserver, ErrorCode.COMMENT_CONTENT_TOO_LONG);
            return;
        }

        try {
            long commentId = commentService.createComment(userId, postId, content.trim(), rootId, parentId);

            CreateCommentResponse response = CreateCommentResponse.newBuilder()
                    .setCommentId(commentId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreateComment failed: userId={} postId={}", userId, postId, e);
            sendError(responseObserver, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public void listComments(ListCommentsRequest request, StreamObserver<ListCommentsResponse> responseObserver) {
        long postId = request.getPostId();
        int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
        long cursor = request.getCursor();

        try {
            CommentsVO result = commentService.listComments(postId, cursor, pageSize);

            ListCommentsResponse.Builder builder = ListCommentsResponse.newBuilder()
                    .setNextCursor(result.getNextCursor())
                    .setHasMore(result.getHasMore());

            for (CommentVO comment : result.getComments()) {
                builder.addComments(CommentResponse.newBuilder()
                        .setCommentId(comment.getCommentId())
                        .setPostId(comment.getPostId())
                        .setUserId(comment.getUserId())
                        .setRootId(comment.getRootId())
                        .setParentId(comment.getParentId())
                        .setReplyToUserId(comment.getReplyToUserId())
                        .setContent(comment.getContent())
                        .setCreatedAt(comment.getCreatedAt())
                        .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListComments failed: postId={}", postId, e);
            sendError(responseObserver, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public void deleteComment(DeleteCommentRequest request, StreamObserver<DeleteCommentResponse> responseObserver) {
        Long userId = extractUserId(request);
        long commentId = request.getCommentId();

        try {
            commentService.deleteComment(commentId, userId);

            DeleteCommentResponse response = DeleteCommentResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("DeleteComment failed: userId={} commentId={}", userId, commentId, e);
            responseObserver.onError(io.grpc.Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void deletePost(DeletePostRequest request, StreamObserver<DeletePostResponse> responseObserver) {
        Long userId = extractUserId(request);
        long postId = request.getPostId();

        try {
            postWriteService.deletePost(postId, userId);

            DeletePostResponse response = DeletePostResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("DeletePost failed: userId={} postId={}", userId, postId, e);
            responseObserver.onError(io.grpc.Status.PERMISSION_DENIED
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getRecommendFeed(GetRecommendFeedRequest request, StreamObserver<GetRecommendFeedResponse> responseObserver) {
        Long userId = extractUserId(request);
        int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
        String cursor = request.getCursor();

        try {
            RecommendFeedVO result = feedService.getRecommendFeed(userId, pageSize, cursor);

            GetRecommendFeedResponse.Builder builder = GetRecommendFeedResponse.newBuilder()
                    .setNextCursor(result.getNextCursor() != null ? result.getNextCursor() : "")
                    .setHasMore(result.getHasMore());

            for (PostDetailVO item : result.getItems()) {
                builder.addItems(PostDetailResponse.newBuilder()
                        .setPostId(item.getPostId())
                        .setUserId(item.getUserId())
                        .setContent(item.getContent())
                        .addAllImageKeys(item.getImageKeys())
                        .setLikeCount(item.getLikeCount())
                        .setCommentCount(item.getCommentCount())
                        .setIsLiked(item.getIsLiked())
                        .setCreatedAt(item.getCreatedAt())
                        .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetRecommendFeed failed: userId={}", userId, e);
            sendError(responseObserver, ErrorCode.INTERNAL_ERROR);
        }
    }

    // ========== Helper Methods ==========

    /**
     * 从 gRPC Context 提取 userId.
     *
     * <p>由 {@link UserIdInterceptor} 把 metadata(x-user-id)注入 Context,
     * 业务侧只需 {@code Context.current().get(...)} 即可.
     *
     * <p>返回 null 表示上游(gateway)未传 userId,业务层应当按未授权处理.
     */
    private Long extractUserId(Object request) {
        try {
            Long userId = UserIdInterceptor.USER_ID_CONTEXT_KEY.get();
            if (userId != null) {
                return userId;
            }
            log.warn("No userId in context, treating as unauthenticated");
        } catch (Exception e) {
            log.warn("Failed to extract userId from context", e);
        }
        return null;
    }

    private void sendError(StreamObserver<?> responseObserver, ErrorCode errorCode) {
        responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription(errorCode.getMessage())
                .asRuntimeException());
    }
}
