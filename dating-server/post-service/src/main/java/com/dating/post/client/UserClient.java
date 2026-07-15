package com.dating.post.client;

import com.dating.user.proto.BatchGetUserProfilesRequest;
import com.dating.user.proto.BatchGetUserProfilesResponse;
import com.dating.user.proto.UserProfileResponse;
import com.dating.user.proto.UserServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User Service Client.
 *
 * <p>调用 user-service gRPC 接口获取用户信息.
 *
 * <p>关键约束:
 * <ul>
 *   <li>本服务不缓存 user-service 返回的资料到 Redis</li>
 *   <li>但允许本地短 TTL Caffeine 缓存(30秒)用于性别查询</li>
 *   <li>user-service 不可用时降级:getFriendUserIds 返空,isMale 默认 false</li>
 * </ul>
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

    /**
     * 获取用户的好友 user_id 列表.
     *
     * <p>用于发帖时做写扩散.
     * user-service 不可用时返回空列表.
     *
     * @param userId 用户ID
     * @return 好友 user_id 列表
     */
    public List<Long> getFriendUserIds(Long userId) {
        try {
            // TODO: 等 user-service 实现好友列表接口后替换
            // 目前返回空列表作为桩实现
            log.debug("getFriendUserIds called for userId={}, returning empty list (stub)", userId);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get friend user ids, userId={}, error={}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 判断用户是否为男性.
     *
     * <p>用于 Feed 池分桶.
     * 命中 Caffeine 30秒缓存.
     * user-service 不可用时默认返回 false(归到女性池,可接受).
     *
     * @param userId 用户ID
     * @return true=男 / false=女
     */
    @Cacheable(value = "userGender", key = "#userId", unless = "#result == null")
    public Boolean isMale(Long userId) {
        try {
            // TODO: 等 user-service 实现性别字段后替换
            // 暂时用 userId % 2 == 0 作为测试
            boolean isMale = userId % 2 == 0;
            log.debug("isMale for userId={}, result={} (stub)", userId, isMale);
            return isMale;
        } catch (Exception e) {
            log.warn("Failed to get gender, userId={}, fallback to false, error={}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 批量获取用户性别.
     *
     * <p>用于 FeedScoreJob 重建池时避免 RPC 风暴.
     * 命中 Caffeine 30秒缓存.
     *
     * @param userIds 用户ID列表
     * @return userId -> isMale 映射
     */
    public Map<Long, Boolean> getGenders(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            // TODO: 等 user-service 实现批量获取性别后替换
            // 暂时用 userId % 2 == 0 作为测试
            return userIds.stream()
                    .collect(Collectors.toMap(
                            userId -> userId,
                            userId -> userId % 2 == 0
                    ));
        } catch (Exception e) {
            log.warn("Failed to get genders for {} users, fallback to all female, error={}",
                    userIds.size(), e.getMessage());
            return userIds.stream()
                    .collect(Collectors.toMap(
                            userId -> userId,
                            userId -> false
                    ));
        }
    }

    /**
     * 批量获取用户资料.
     *
     * <p>用于 Feed 展示时获取作者信息.
     *
     * @param userIds 用户ID列表
     * @return 用户资料列表
     */
    public List<UserProfileResponse> getUserProfiles(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            BatchGetUserProfilesRequest request = BatchGetUserProfilesRequest.newBuilder()
                    .addAllUserIds(userIds)
                    .build();

            UserServiceGrpc.UserServiceBlockingStub stub = createStub();
            BatchGetUserProfilesResponse response = stub.batchGetUserProfiles(request);

            return response.getProfilesList();
        } catch (Exception e) {
            log.warn("Failed to batch get user profiles, count={}, error={}",
                    userIds.size(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
