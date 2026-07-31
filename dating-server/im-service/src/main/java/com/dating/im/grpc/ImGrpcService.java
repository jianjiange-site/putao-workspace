package com.dating.im.grpc;

import com.dating.im.proto.*;
import com.dating.im.service.TokenService;
import com.dating.im.service.CallbackService;
import com.dating.im.service.PresenceService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * IM gRPC 服务端实现.
 *
 * <p>实现 im.proto 定义的 RPC 接口.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class ImGrpcService extends ImServiceGrpc.ImServiceImplBase {

    private final TokenService tokenService;
    private final CallbackService callbackService;
    private final PresenceService presenceService;

    @Override
    public void sendMessage(SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
        Long senderId = request.getSenderId();
        Long receiverId = request.getReceiverId();
        String content = request.getContent();
        String messageType = request.getMessageType();

        log.info("SendMessage: {} -> {}, type={}", senderId, receiverId, messageType);

        try {
            // TODO: 实现真正的消息发送
            // 1. 构建消息结构
            // 2. 调用 OpenIM API 发送
            // 3. 落库

            SendMessageResponse response = SendMessageResponse.newBuilder()
                    .setMessageId("msg_" + System.currentTimeMillis())
                    .setCreatedAt(System.currentTimeMillis() / 1000)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("SendMessage failed: {} -> {}", senderId, receiverId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getImToken(GetImTokenRequest request, StreamObserver<GetImTokenResponse> responseObserver) {
        Long userId = request.getUserId();
        String nickname = request.getNickname();
        String avatarKey = request.getAvatarKey();

        log.info("GetImToken: userId={}", userId);

        try {
            TokenService.ImTokenResult result = tokenService.getImToken(userId, nickname, avatarKey);

            GetImTokenResponse.Builder builder = GetImTokenResponse.newBuilder()
                    .setImToken(result.token() != null ? result.token() : "")
                    .setExpireSeconds(result.expireSeconds());

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetImToken failed: userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void onRawCallback(OnRawCallbackRequest request, StreamObserver<OnRawCallbackResponse> responseObserver) {
        String provider = request.getProvider();
        byte[] payload = request.getPayload().toByteArray();

        log.info("OnRawCallback: provider={}, payloadSize={}", provider, payload.length);

        try {
            int code = callbackService.handleRawCallback(provider, payload);

            OnRawCallbackResponse response = OnRawCallbackResponse.newBuilder()
                    .setCode(code)
                    .setMessage(com.dating.im.exception.ImErrorCode.getMessage(code))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("OnRawCallback failed: provider={}", provider, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void generateCallToken(GenerateCallTokenRequest request,
                                  StreamObserver<GenerateCallTokenResponse> responseObserver) {
        Long userId = request.getUserId();
        Long peerId = request.getPeerId();

        log.info("GenerateCallToken: userId={}, peerId={}", userId, peerId);

        try {
            String token = tokenService.generateCallToken(userId, peerId);

            GenerateCallTokenResponse response = GenerateCallTokenResponse.newBuilder()
                    .setToken(token)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GenerateCallToken failed: userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listOnlineUsers(ListOnlineUsersRequest request,
                                StreamObserver<ListOnlineUsersResponse> responseObserver) {
        long since = request.getSince();
        long until = request.getUntil();
        int limit = request.getLimit() > 0 ? request.getLimit() : 5000;

        log.info("ListOnlineUsers: since={}, until={}, limit={}", since, until, limit);

        try {
            java.util.List<Long> userIds = presenceService.listOnlineUsers(since, until, limit);

            ListOnlineUsersResponse.Builder builder = ListOnlineUsersResponse.newBuilder()
                    .addAllUserIds(userIds);

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListOnlineUsers failed", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listRecentOfflineUsers(ListRecentOfflineUsersRequest request,
                                       StreamObserver<ListRecentOfflineUsersResponse> responseObserver) {
        long since = request.getSince();
        long until = request.getUntil();
        int limit = request.getLimit() > 0 ? request.getLimit() : 5000;

        log.info("ListRecentOfflineUsers: since={}, until={}, limit={}", since, until, limit);

        try {
            java.util.List<Long> userIds = presenceService.listRecentOfflineUsers(since, until, limit);

            ListRecentOfflineUsersResponse.Builder builder = ListRecentOfflineUsersResponse.newBuilder()
                    .addAllUserIds(userIds);

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListRecentOfflineUsers failed", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }
}
