package com.dating.post.client;

import com.dating.post.constant.RedisKey;
import com.dating.user.proto.BatchGetProfilesRequest;
import com.dating.user.proto.BatchGetUserProfilesRequest;
import com.dating.user.proto.Gender;
import com.dating.user.proto.GetUserProfileRequest;
import com.dating.user.proto.UserProfileProto;
import com.dating.user.proto.UserProfileResponse;
import com.dating.user.proto.UserServiceGrpc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * user-service gRPC Client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserClient {

    private static final Duration GENDER_REDIS_TTL = Duration.ofHours(6);

    private final UserServiceGrpc.UserServiceBlockingStub userServiceStub;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * user proto 目前还没有好友列表 RPC；保留扩散接口边界，未接通前返回空。
     */
    public List<Long> getFriendUserIds(Long userId) {
        log.debug("getFriendUserIds is not available in user.proto: userId={}", userId);
        return Collections.emptyList();
    }

    /**
     * L1 Caffeine -> L2 Redis -> user-service.
     */
    @Cacheable(value = "userGender", key = "#userId")
    public Boolean isMale(Long userId) {
        String cacheKey = RedisKey.userGender(userId);
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return "1".equals(cached);
            }
            UserProfileProto profile = userServiceStub.getProfile(
                    GetUserProfileRequest.newBuilder().setUserId(userId).build());
            boolean male = profile.getGender() == Gender.GENDER_MALE;
            stringRedisTemplate.opsForValue().set(
                    cacheKey, male ? "1" : "0", GENDER_REDIS_TTL);
            return male;
        } catch (Exception e) {
            log.warn("Get user gender failed, fallback to female bucket: userId={} error={}",
                    userId, e.getMessage());
            return false;
        }
    }

    public Map<Long, Boolean> getGenders(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Boolean> result = new HashMap<>();
        List<Long> misses = userIds.stream()
                .filter(userId -> {
                    String cached = stringRedisTemplate.opsForValue()
                            .get(RedisKey.userGender(userId));
                    if (cached == null) {
                        return true;
                    }
                    result.put(userId, "1".equals(cached));
                    return false;
                })
                .toList();

        if (!misses.isEmpty()) {
            try {
                var response = userServiceStub.batchGetProfile(
                        BatchGetProfilesRequest.newBuilder()
                                .addAllUserIds(misses)
                                .setIncludeInterests(false)
                                .build());
                for (UserProfileProto profile : response.getProfilesList()) {
                    boolean male = profile.getGender() == Gender.GENDER_MALE;
                    result.put(profile.getUserId(), male);
                    stringRedisTemplate.opsForValue().set(
                            RedisKey.userGender(profile.getUserId()),
                            male ? "1" : "0",
                            GENDER_REDIS_TTL);
                }
            } catch (Exception e) {
                log.warn("Batch get genders failed: count={} error={}",
                        misses.size(), e.getMessage());
            }
        }
        for (Long userId : userIds) {
            result.putIfAbsent(userId, false);
        }
        return result;
    }

    public List<UserProfileResponse> getUserProfiles(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        try {
            return userServiceStub.batchGetUserProfiles(
                    BatchGetUserProfilesRequest.newBuilder()
                            .addAllUserIds(userIds)
                            .build())
                    .getProfilesList();
        } catch (Exception e) {
            log.warn("Batch get user profiles failed: count={} error={}",
                    userIds.size(), e.getMessage());
            return List.of();
        }
    }
}
