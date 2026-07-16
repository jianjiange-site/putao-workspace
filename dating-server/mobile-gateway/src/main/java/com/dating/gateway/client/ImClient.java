package com.dating.gateway.client;

import com.dating.im.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ImClient {

    @Value("${im.service.grpc.host:localhost}")
    private String imServiceHost;

    @Value("${im.service.grpc.port:19091}")
    private int imServicePort;

    private ImServiceGrpc.ImServiceBlockingStub createStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(imServiceHost, imServicePort)
                .usePlaintext()
                .build();
        return ImServiceGrpc.newBlockingStub(channel);
    }

    public GetImTokenResponse getImToken(Long userId, String nickname, String avatarKey) {
        log.info("getImToken called for userId={}", userId);
        GetImTokenRequest request = GetImTokenRequest.newBuilder()
                .setUserId(userId)
                .setNickname(nickname)
                .setAvatarKey(avatarKey)
                .build();
        return createStub().getImToken(request);
    }

    public SendMessageResponse sendMessage(Long senderId, Long receiverId, String content, String messageType) {
        log.info("sendMessage from {} to {}", senderId, receiverId);
        SendMessageRequest request = SendMessageRequest.newBuilder()
                .setSenderId(senderId)
                .setReceiverId(receiverId)
                .setContent(content)
                .setMessageType(messageType)
                .build();
        return createStub().sendMessage(request);
    }
}
