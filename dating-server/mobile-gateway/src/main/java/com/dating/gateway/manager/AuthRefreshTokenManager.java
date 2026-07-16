package com.dating.gateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.gateway.entity.AuthRefreshTokenEntity;
import com.dating.gateway.mapper.AuthRefreshTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRefreshTokenManager {

    private final AuthRefreshTokenMapper refreshTokenMapper;

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
        refreshTokenMapper.update(null,
                new LambdaQueryWrapper<AuthRefreshTokenEntity>()
                        .eq(AuthRefreshTokenEntity::getUserId, userId)
                        .eq(AuthRefreshTokenEntity::getDeviceId, deviceId)
                        .isNull(AuthRefreshTokenEntity::getRevokedAt)
                        .eq(AuthRefreshTokenEntity::getDeleted, 0)
                        .allEq(new java.util.HashMap<String, Object>() {{
                            put("revoked_at", Instant.now());
                            put("updated_at", Instant.now());
                        }})
        );
    }
}
