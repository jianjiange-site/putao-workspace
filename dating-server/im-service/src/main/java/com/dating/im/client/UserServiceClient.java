package com.dating.im.client;

import com.dating.user.proto.GetUserTypeRequest;
import com.dating.user.proto.GetUserTypeResponse;
import com.dating.user.proto.UserType;
import com.dating.user.proto.UserServiceGrpc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User Service Client.
 *
 * <p>调用 user-service gRPC 接口获取用户类型(BH/DH).
 */
@Slf4j
@Component
public class UserServiceClient {

    private final UserServiceGrpc.UserServiceBlockingStub stub;

    /** 本地缓存: userId -> isDh */
    private final Map<Long, Boolean> userTypeCache = new ConcurrentHashMap<>();

    public UserServiceClient(UserServiceGrpc.UserServiceBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * 获取用户类型.
     *
     * @param userId 用户ID
     * @return true=数字人(DH) / false=真人(BH)
     */
    public boolean isDigitalHuman(Long userId) {
        Boolean cached = userTypeCache.get(userId);
        if (cached != null) {
            return cached;
        }

        try {
            GetUserTypeRequest request = GetUserTypeRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            GetUserTypeResponse response = stub.getUserType(request);
            boolean isDh = response.getUserType() == UserType.USER_TYPE_DH;
            userTypeCache.put(userId, isDh);
            return isDh;
        } catch (Exception e) {
            log.warn("Failed to get user type, userId={}, fallback to false (BH)", userId, e);
            userTypeCache.put(userId, false);
            return false;
        }
    }

    /**
     * 批量获取用户类型.
     *
     * @param userIds 用户ID列表
     * @return userId -> isDh 映射
     */
    public Map<Long, Boolean> getUserTypes(java.util.List<Long> userIds) {
        Map<Long, Boolean> result = new ConcurrentHashMap<>();
        for (Long userId : userIds) {
            result.put(userId, isDigitalHuman(userId));
        }
        return result;
    }

    /** 清除缓存 */
    public void clearCache() {
        userTypeCache.clear();
    }
}
