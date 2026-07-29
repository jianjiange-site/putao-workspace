package com.dating.im.client;

import com.dating.im.constant.ImRedisKey;
import com.dating.user.proto.GetUserTypeRequest;
import com.dating.user.proto.GetUserTypeResponse;
import com.dating.user.proto.UserType;
import com.dating.user.proto.UserServiceGrpc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * User Service Client.
 *
 * <p>调用 user-service gRPC 接口获取用户类型(BH/DH).
 * <p>用户类型采用 Redis 缓存,10min TTL,多实例共享.
 */
@Slf4j
@Component
public class UserServiceClient {

    private static final long USER_TYPE_CACHE_TTL_MINUTES = 10;

    private final UserServiceGrpc.UserServiceBlockingStub stub;
    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceClient(UserServiceGrpc.UserServiceBlockingStub stub,
                              StringRedisTemplate stringRedisTemplate) {
        this.stub = stub;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取用户类型.
     *
     * @param userId 用户ID
     * @return true=数字人(DH) / false=真人(BH)
     */
    public boolean isDigitalHuman(Long userId) {
        // 1. 尝试从 Redis 缓存获取
        String key = ImRedisKey.userType(userId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("isDigitalHuman cache hit: userId={}", userId);
            return "1".equals(cached);
        }

        // 2. 缓存未命中，调用 gRPC
        try {
            GetUserTypeRequest request = GetUserTypeRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            GetUserTypeResponse response = stub.getUserType(request);
            boolean isDh = response.getUserType() == UserType.USER_TYPE_DH;

            // 3. 回填 Redis 缓存
            stringRedisTemplate.opsForValue().set(key, isDh ? "1" : "0",
                    USER_TYPE_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

            return isDh;
        } catch (Exception e) {
            log.warn("Failed to get user type via gRPC, userId={}, fallback to false (BH)", userId, e);
            return false;
        }
    }

    /**
     * 批量获取用户类型.
     *
     * @param userIds 用户ID列表
     * @return userId -> isDh 映射
     */
    public Map<Long, Boolean> getUserTypes(List<Long> userIds) {
        Map<Long, Boolean> result = new HashMap<>();
        for (Long userId : userIds) {
            result.put(userId, isDigitalHuman(userId));
        }
        return result;
    }

    /**
     * 清除指定用户的缓存(当用户类型变更时调用).
     */
    public void evictCache(Long userId) {
        String key = ImRedisKey.userType(userId);
        stringRedisTemplate.delete(key);
        log.debug("UserType cache evicted: userId={}", userId);
    }
}
