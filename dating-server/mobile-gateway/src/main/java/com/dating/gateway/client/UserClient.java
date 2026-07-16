package com.dating.gateway.client;

import com.dating.user.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * User Service gRPC Client.
 */
@Slf4j
@Component
public class UserClient {

    @Value("${user.service.grpc.host:localhost}")
    private String userServiceHost;

    @Value("${user.service.grpc.port:19090}")
    private int userServicePort;

    private UserServiceGrpc.UserServiceBlockingStub createStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(userServiceHost, userServicePort)
                .usePlaintext()
                .build();
        return UserServiceGrpc.newBlockingStub(channel);
    }

    public UserProfileResponse getUserProfile(Long userId) {
        log.info("getUserProfile called for userId={}", userId);
        GetUserProfileRequest request = GetUserProfileRequest.newBuilder()
                .setUserId(userId)
                .build();
        return createStub().getUserProfile(request);
    }

    public UserProfileResponse updateUserProfile(Long userId, String nickname, String avatarKey, String bio) {
        log.info("updateUserProfile called for userId={}", userId);
        UpdateUserProfileRequest.Builder builder = UpdateUserProfileRequest.newBuilder()
                .setUserId(userId);
        if (nickname != null) builder.setNickname(nickname);
        if (avatarKey != null) builder.setAvatarKey(avatarKey);
        if (bio != null) builder.setBio(bio);
        return createStub().updateUserProfile(builder.build());
    }

    public List<UserProfileResponse> batchGetUserProfiles(List<Long> userIds) {
        log.info("batchGetUserProfiles called for {} users", userIds.size());
        BatchGetUserProfilesRequest request = BatchGetUserProfilesRequest.newBuilder()
                .addAllUserIds(userIds)
                .build();
        BatchGetUserProfilesResponse response = createStub().batchGetUserProfiles(request);
        return response.getProfilesList();
    }
}
