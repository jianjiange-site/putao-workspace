package com.dating.user.manager;

import com.dating.user.entity.UserLoginPhoneEntity;
import com.dating.user.mapper.UserLoginPhoneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * UserLoginPhone Manager — 手机号绑定表读写.
 *
 * <p>唯一约束 (phone_e164, app_name) 由 DB 强制;插入冲突时抛 {@code DuplicateKeyException}
 * 由 service 层捕获后做"已存在"判定.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserLoginPhoneManager {

    private final UserLoginPhoneMapper userLoginPhoneMapper;

    public UserLoginPhoneEntity findByPhoneAndApp(String phoneE164, String appName) {
        if (phoneE164 == null || appName == null) return null;
        return userLoginPhoneMapper.findByPhoneAndApp(phoneE164, appName);
    }

    public void insert(Long userId, String phoneE164, String appName) {
        UserLoginPhoneEntity entity = new UserLoginPhoneEntity();
        entity.setUserId(userId);
        entity.setPhoneE164(phoneE164);
        entity.setAppName(appName);
        entity.setVerifiedAt(Instant.now());
        userLoginPhoneMapper.insert(entity);
    }
}
