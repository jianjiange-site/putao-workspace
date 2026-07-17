package com.dating.gateway.client;

import com.dating.post.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PostClient {

    private static final Logger log = LoggerFactory.getLogger(PostClient.class);

    @Value("${post.service.grpc.host:localhost}")
    private String postServiceHost;

    @Value("${post.service.grpc.port:19084}")
    private int postServicePort;

    private PostServiceGrpc.PostServiceBlockingStub createStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(postServiceHost, postServicePort)
                .usePlaintext()
                .build();
        return PostServiceGrpc.newBlockingStub(channel);
    }

    public CreatePostResponse createPost(String content, List<String> imageKeys) {
        log.info("createPost: content length={}", content != null ? content.length() : 0);
        CreatePostRequest.Builder builder = CreatePostRequest.newBuilder().setContent(content);
        if (imageKeys != null) builder.addAllImageKeys(imageKeys);
        return createStub().createPost(builder.build());
    }

    public PostDetailResponse getPostDetail(Long postId) {
        log.info("getPostDetail called for postId={}", postId);
        GetPostDetailRequest request = GetPostDetailRequest.newBuilder().setPostId(postId).build();
        return createStub().getPostDetail(request);
    }

    public ListUserPostsResponse listUserPosts(Long userId, int pageSize, long cursor) {
        log.info("listUserPosts called for userId={}", userId);
        ListUserPostsRequest request = ListUserPostsRequest.newBuilder()
                .setUserId(userId).setPageSize(pageSize).setCursor(cursor).build();
        return createStub().listUserPosts(request);
    }

    public ActionLikeResponse actionLike(Long postId, boolean like) {
        log.info("actionLike called: postId={}, like={}", postId, like);
        ActionLikeRequest request = ActionLikeRequest.newBuilder()
                .setPostId(postId)
                .setAction(like ? LikeAction.LIKE : LikeAction.UNLIKE)
                .build();
        return createStub().actionLike(request);
    }

    public CreateCommentResponse createComment(Long postId, String content, long rootId, long parentId) {
        log.info("createComment for postId={}", postId);
        CreateCommentRequest request = CreateCommentRequest.newBuilder()
                .setPostId(postId).setContent(content).setRootId(rootId).setParentId(parentId).build();
        return createStub().createComment(request);
    }

    public ListCommentsResponse listComments(Long postId, int pageSize, long cursor) {
        log.info("listComments for postId={}", postId);
        ListCommentsRequest request = ListCommentsRequest.newBuilder()
                .setPostId(postId).setPageSize(pageSize).setCursor(cursor).build();
        return createStub().listComments(request);
    }

    public DeleteCommentResponse deleteComment(Long commentId) {
        log.info("deleteComment for commentId={}", commentId);
        DeleteCommentRequest request = DeleteCommentRequest.newBuilder().setCommentId(commentId).build();
        return createStub().deleteComment(request);
    }

    public DeletePostResponse deletePost(Long postId) {
        log.info("deletePost for postId={}", postId);
        DeletePostRequest request = DeletePostRequest.newBuilder().setPostId(postId).build();
        return createStub().deletePost(request);
    }

    public GetRecommendFeedResponse getRecommendFeed(int pageSize, String cursor) {
        log.info("getRecommendFeed: pageSize={}", pageSize);
        GetRecommendFeedRequest request = GetRecommendFeedRequest.newBuilder()
                .setPageSize(pageSize).setCursor(cursor != null ? cursor : "").build();
        return createStub().getRecommendFeed(request);
    }
}
