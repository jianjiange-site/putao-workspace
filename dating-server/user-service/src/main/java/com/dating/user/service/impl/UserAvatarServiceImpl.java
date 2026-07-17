package com.dating.user.service.impl;

import com.dating.user.constant.ErrorCode;
import com.dating.user.dto.ConfirmAvatarDTO;
import com.dating.user.dto.PresignAvatarDTO;
import com.dating.user.exception.BizException;
import com.dating.user.exception.UserNotFoundException;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.manager.UserProfileCacheManager;
import com.dating.user.service.UserAvatarService;
import com.dating.user.service.UserProfileService;
import com.dating.user.vo.PresignResultVO;
import com.dating.user.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * UserAvatar Service 实现 — MVP 简化版.
 *
 * <p>本服务没有引入 dating-common(ObjectStorage),MVP 阶段只生成 object_key 并返回
 * 一个占位 URL;真正接对象存储时,把 {@link #presignUrlPlaceholder(String)} 替换成
 * {@code objectStorage.presignedPutUrl(...)} 即可.
 *
 * <p>头像字段 custom_avatar 在 V1 表中未建(V2 加列),MVP 仅清缓存后返回当前 profile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAvatarServiceImpl implements UserAvatarService {

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    /** 单文件 10MB */
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Duration TTL = Duration.ofMinutes(5);

    private final UserInfoManager userInfoManager;
    private final UserProfileCacheManager cacheManager;
    private final UserProfileService userProfileService;

    @Override
    public PresignResultVO presignUpload(Long userId, PresignAvatarDTO dto) {
        // 1. 校验
        if (userId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "userId required");
        }
        if (dto.getExt() == null) {
            throw new BizException(ErrorCode.AVATAR_EXT_INVALID);
        }
        String ext = dto.getExt().toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.AVATAR_EXT_INVALID);
        }
        if (dto.getSizeBytes() != null && dto.getSizeBytes() > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.AVATAR_SIZE_EXCEEDED);
        }

        // 2. 校验用户存在
        if (userInfoManager.findByUserId(userId) == null) {
            throw new UserNotFoundException(userId);
        }

        // 3. object_key 模板 avatar/{userId}/{uuid}.{ext}
        String objectKey = String.format("avatar/%d/%s.%s",
                userId, UUID.randomUUID().toString(), ext);

        // 4. 签 URL(MVP 占位)— 接 ObjectStorage 后改成 objectStorage.presignedPutUrl
        String presignedUrl = presignUrlPlaceholder(objectKey);
        long expiresAtMs = Instant.now().plus(TTL).toEpochMilli();

        return PresignResultVO.builder()
                .presignedUrl(presignedUrl)
                .objectKey(objectKey)
                .expiresAtMs(expiresAtMs)
                .build();
    }

    @Override
    public UserProfileVO confirmUpload(Long userId, ConfirmAvatarDTO dto) {
        if (userId == null || dto.getObjectKey() == null || dto.getObjectKey().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "objectKey required");
        }
        if (userInfoManager.findByUserId(userId) == null) {
            throw new UserNotFoundException(userId);
        }
        // 1. 前缀校验:objectKey 必须以 avatar/{userId}/ 开头,防越权覆盖别人头像
        String prefix = "avatar/" + userId + "/";
        if (!dto.getObjectKey().startsWith(prefix)) {
            throw new BizException(ErrorCode.AVATAR_OBJECT_KEY_MISMATCH);
        }
        // 2. MVP:不真实调用 headObject,直接落库逻辑留给 V2 加列.
        //    仅清缓存等下次 GetProfile 重新加载.
        cacheManager.evictAll(userId);
        log.info("ConfirmAvatarUpload userId={} objectKey={}", userId, dto.getObjectKey());
        return userProfileService.getProfile(userId);
    }

    /**
     * MVP 占位: 真实接对象存储时,应改为 {@code objectStorage.presignedPutUrl(bucket, key, TTL)}.
     */
    private String presignUrlPlaceholder(String objectKey) {
        return "https://minio-api.jianjiange.site/dating-user-dev/" + objectKey
                + "?X-Amz-Expires=300&X-Amz-Signature=PLACEHOLDER";
    }
}