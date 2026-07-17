package com.dating.user.manager;

import com.dating.user.entity.UserDeviceRegistrationEntity;
import com.dating.user.mapper.UserDeviceRegistrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * UserDeviceRegistration Manager — 设备绑定表读写.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDeviceManager {

    private final UserDeviceRegistrationMapper userDeviceRegistrationMapper;

    public UserDeviceRegistrationEntity findActive(String deviceId, Integer platform, String appName) {
        if (deviceId == null || platform == null || appName == null) return null;
        return userDeviceRegistrationMapper.findActive(deviceId, platform, appName);
    }

    public void insert(Long userId, String deviceId, Integer platform, String appName) {
        UserDeviceRegistrationEntity entity = new UserDeviceRegistrationEntity();
        entity.setUserId(userId);
        entity.setDeviceId(deviceId);
        entity.setPlatform(platform);
        entity.setAppName(appName);
        userDeviceRegistrationMapper.insert(entity);
    }
}
