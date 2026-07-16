package com.dating.gateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.gateway.entity.AuthDeviceEntity;
import com.dating.gateway.mapper.AuthDeviceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** Auth Device Manager. */
@Slf4j
@Component
public class AuthDeviceManager {

    private final AuthDeviceMapper authDeviceMapper;

    public AuthDeviceManager(AuthDeviceMapper authDeviceMapper) {
        this.authDeviceMapper = authDeviceMapper;
    }

    public AuthDeviceEntity upsertDevice(Long userId, String deviceId, Integer platform,
            String deviceModel, String osVersion, String appVersion, String pushToken) {
        LambdaQueryWrapper<AuthDeviceEntity> query = new LambdaQueryWrapper<>();
        query.eq(AuthDeviceEntity::getUserId, userId)
                .eq(AuthDeviceEntity::getDeviceId, deviceId);
        
        AuthDeviceEntity entity = authDeviceMapper.selectOne(query);
        if (entity == null) {
            entity = new AuthDeviceEntity();
            entity.setUserId(userId);
            entity.setDeviceId(deviceId);
            entity.setPlatform(platform);
            entity.setDeviceModel(deviceModel);
            entity.setOsVersion(osVersion);
            entity.setAppVersion(appVersion);
            entity.setPushToken(pushToken);
            entity.setLoginCount(1);
            entity.setLastLoginAt(Instant.now());
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            authDeviceMapper.insert(entity);
        } else {
            entity.setLoginCount(entity.getLoginCount() != null ? entity.getLoginCount() + 1 : 1);
            entity.setLastLoginAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            if (pushToken != null) entity.setPushToken(pushToken);
            if (osVersion != null) entity.setOsVersion(osVersion);
            if (appVersion != null) entity.setAppVersion(appVersion);
            authDeviceMapper.updateById(entity);
        }
        return entity;
    }

    public Optional<AuthDeviceEntity> findByUserIdAndDeviceId(Long userId, String deviceId) {
        LambdaQueryWrapper<AuthDeviceEntity> query = new LambdaQueryWrapper<>();
        query.eq(AuthDeviceEntity::getUserId, userId)
                .eq(AuthDeviceEntity::getDeviceId, deviceId);
        return Optional.ofNullable(authDeviceMapper.selectOne(query));
    }
}
