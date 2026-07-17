package com.dating.gateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.gateway.entity.AuthRefreshTokenEntity;
import com.dating.gateway.mapper.AuthRefreshTokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class AuthRefreshTokenManager {

    private static final Logger log = LoggerFactory.getLogger(AuthRefreshTokenManager.class);

    private final AuthRefreshTokenMapper refreshTokenMapper;

    public AuthRefreshTokenManager(AuthRefreshTokenMapper refreshTokenMapper) {
        this.refreshTokenMapper = refreshTokenMapper;
    }

    public Optional<AuthRefreshTokenEntity> findValidByTokenHash(String tokenHash) {
        return Optional.ofNullable(
                refreshTokenMapper.selectOne(
                        new LambdaQueryWrapper<AuthRefreshTokenEntity>()
                                .eq(AuthRefreshTokenEntity::getTokenHash, tokenHash)
                                .isNull(AuthRefreshTokenEntity::getUsedAt)
                                .isNull(AuthRefreshTokenEntity::getRevokedAt)
                                .eq(AuthRefreshTokenEntity::getDeleted, 0)
                )
        );
    }

    public void saveRefreshToken(AuthRefreshTokenEntity entity) {
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        refreshTokenMapper.insert(entity);
    }

    public void markUsed(AuthRefreshTokenEntity entity) {
        entity.setUsedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        refreshTokenMapper.updateById(entity);
    }

    public void revokeAllForUser(Long userId, String deviceId) {
        var now = Instant.now();
        LambdaUpdateWrapper<AuthRefreshTokenEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AuthRefreshTokenEntity::getUserId, userId)
                .eq(AuthRefreshTokenEntity::getDeviceId, deviceId)
                .isNull(AuthRefreshTokenEntity::getRevokedAt)
                .eq(AuthRefreshTokenEntity::getDeleted, 0)
                .set(AuthRefreshTokenEntity::getRevokedAt, now)
                .set(AuthRefreshTokenEntity::getUpdatedAt, now);
        refreshTokenMapper.update(null, updateWrapper);
    }
}
