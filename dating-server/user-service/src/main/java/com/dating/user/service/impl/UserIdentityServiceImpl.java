package com.dating.user.service.impl;

import com.dating.user.config.SnowflakeIdConfig.SnowflakeIdGenerator;
import com.dating.user.constant.ErrorCode;
import com.dating.user.constant.RedisKey;
import com.dating.user.dto.ResolveOrCreateDTO;
import com.dating.user.entity.UserInfoEntity;
import com.dating.user.exception.BizException;
import com.dating.user.manager.UserBanManager;
import com.dating.user.manager.UserDeviceManager;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.manager.UserLoginPhoneManager;
import com.dating.user.manager.UserThirdPartyManager;
import com.dating.user.proto.BanReason;
import com.dating.user.service.UserIdentityService;
import com.dating.user.vo.BanStatusVO;
import com.dating.user.vo.ResolveOrCreateVO;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * UserIdentity Service 实现.
 *
 * <p>对应设计文档 §5.3 三套 ResolveOrCreate 流程,每条都有 Redisson 注册锁兜底.
 *
 * <p>placeholder 字段约定:
 * <ul>
 *   <li>{@code nickname} = "User_${userId}"</li>
 *   <li>{@code gender} = 0</li>
 *   <li>{@code regulation_status} = 0</li>
 *   <li>{@code pending} = 1</li>
 *   <li>{@code user_type} = 1(BH,DH 由 ai-chat 注入)</li>
 *   <li>{@code last_open_at} = NOW()</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentityServiceImpl implements UserIdentityService {

    /** Redisson 锁 wait / lease 时间(秒) */
    private static final long LOCK_WAIT_SECONDS = 3;
    private static final long LOCK_LEASE_SECONDS = 30;

    private final UserInfoManager userInfoManager;
    private final UserLoginPhoneManager userLoginPhoneManager;
    private final UserThirdPartyManager userThirdPartyManager;
    private final UserDeviceManager userDeviceManager;
    private final UserBanManager userBanManager;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final RedissonClient redissonClient;
    private final PhoneNumberUtil phoneNumberUtil;

    // ============ ResolveOrCreateByPhone ============

    @Override
    public ResolveOrCreateVO resolveOrCreateByPhone(ResolveOrCreateDTO dto) {
        // 1. libphonenumber 二次校验 + 规范化
        String phoneE164 = normalizePhone(dto.getPhoneE164());
        String appName = requireAppName(dto.getAppName());

        // 2. 加锁
        RLock lock = redissonClient.getLock(RedisKey.lockRegisterPhone(phoneE164, appName));
        tryLock(lock);

        try {
            // 3. 二次检查
            var existing = userLoginPhoneManager.findByPhoneAndApp(phoneE164, appName);
            if (existing != null) {
                userInfoManager.touchLastOpenAt(existing.getUserId());
                return ResolveOrCreateVO.builder()
                        .userId(existing.getUserId())
                        .pending(userInfoManager.findByUserId(existing.getUserId()).getPending() == 1)
                        .newlyCreated(false)
                        .build();
            }
            // 4. 未命中 → 创建 placeholder + 绑定
            Long userId = createPlaceholder();
            try {
                userLoginPhoneManager.insert(userId, phoneE164, appName);
            } catch (DuplicateKeyException dup) {
                // 并发插入冲突,重读
                existing = userLoginPhoneManager.findByPhoneAndApp(phoneE164, appName);
                if (existing != null) {
                    userInfoManager.touchLastOpenAt(existing.getUserId());
                    return ResolveOrCreateVO.builder()
                            .userId(existing.getUserId())
                            .pending(userInfoManager.findByUserId(existing.getUserId()).getPending() == 1)
                            .newlyCreated(false)
                            .build();
                }
                throw dup;
            }
            log.info("ResolveOrCreateByPhone created placeholder: userId={} phone={}", userId, phoneE164);
            return ResolveOrCreateVO.builder()
                    .userId(userId)
                    .pending(true)
                    .newlyCreated(true)
                    .build();
        } finally {
            safeUnlock(lock);
        }
    }

    // ============ ResolveOrCreateByThirdParty ============

    @Override
    public ResolveOrCreateVO resolveOrCreateByThirdParty(ResolveOrCreateDTO dto) {
        Integer platform = requirePlatform(dto.getPlatform());
        String thirdPartyUserId = requireNonBlank(dto.getThirdPartyUserId(), "thirdPartyUserId");
        String appName = requireAppName(dto.getAppName());

        RLock lock = redissonClient.getLock(
                RedisKey.lockRegisterThirdParty(platform, thirdPartyUserId, appName));
        tryLock(lock);

        try {
            var existing = userThirdPartyManager.findActive(platform, thirdPartyUserId, appName);
            if (existing != null) {
                userInfoManager.touchLastOpenAt(existing.getUserId());
                return ResolveOrCreateVO.builder()
                        .userId(existing.getUserId())
                        .pending(userInfoManager.findByUserId(existing.getUserId()).getPending() == 1)
                        .newlyCreated(false)
                        .build();
            }
            Long userId = createPlaceholder();
            try {
                userThirdPartyManager.insert(userId, platform, thirdPartyUserId, appName, dto.getGoogleEmail());
            } catch (DuplicateKeyException dup) {
                existing = userThirdPartyManager.findActive(platform, thirdPartyUserId, appName);
                if (existing != null) {
                    userInfoManager.touchLastOpenAt(existing.getUserId());
                    return ResolveOrCreateVO.builder()
                            .userId(existing.getUserId())
                            .pending(userInfoManager.findByUserId(existing.getUserId()).getPending() == 1)
                            .newlyCreated(false)
                            .build();
                }
                throw dup;
            }
            log.info("ResolveOrCreateByThirdParty created placeholder: userId={} platform={}",
                    userId, platform);
            return ResolveOrCreateVO.builder()
                    .userId(userId)
                    .pending(true)
                    .newlyCreated(true)
                    .build();
        } finally {
            safeUnlock(lock);
        }
    }

    // ============ ResolveOrCreateByDevice ============

    @Override
    public ResolveOrCreateVO resolveOrCreateByDevice(ResolveOrCreateDTO dto) {
        Integer platform = requirePlatform(dto.getPlatform());
        String deviceId = requireNonBlank(dto.getDeviceId(), "deviceId");
        String appName = requireAppName(dto.getAppName());

        RLock lock = redissonClient.getLock(
                RedisKey.lockRegisterDevice(deviceId, platform, appName));
        tryLock(lock);

        try {
            var existing = userDeviceManager.findActive(deviceId, platform, appName);
            if (existing != null) {
                userInfoManager.touchLastOpenAt(existing.getUserId());
                var info = userInfoManager.findByUserId(existing.getUserId());
                return ResolveOrCreateVO.builder()
                        .userId(existing.getUserId())
                        .pending(info != null && info.getPending() == 1)
                        .newlyCreated(false)
                        .build();
            }
            Long userId = createPlaceholder();
            try {
                userDeviceManager.insert(userId, deviceId, platform, appName);
            } catch (DuplicateKeyException dup) {
                existing = userDeviceManager.findActive(deviceId, platform, appName);
                if (existing != null) {
                    userInfoManager.touchLastOpenAt(existing.getUserId());
                    return ResolveOrCreateVO.builder()
                            .userId(existing.getUserId())
                            .pending(userInfoManager.findByUserId(existing.getUserId()).getPending() == 1)
                            .newlyCreated(false)
                            .build();
                }
                throw dup;
            }
            log.info("ResolveOrCreateByDevice created placeholder: userId={} platform={}", userId, platform);
            return ResolveOrCreateVO.builder()
                    .userId(userId)
                    .pending(true)
                    .newlyCreated(true)
                    .build();
        } finally {
            safeUnlock(lock);
        }
    }

    // ============ CheckBan ============

    @Override
    public BanStatusVO checkBan(Long userId) {
        if (userId == null) {
            return BanStatusVO.builder().banned(false).reason("NONE").build();
        }

        // 1. 短缓存
        BanReason cached = userBanManager.queryBanReason(userId);
        if (cached != null) {
            return toBanStatusVO(cached);
        }

        // 2. 运营级封禁
        if (userBanManager.isOperationalBanned(userId)) {
            userBanManager.cacheBanReason(userId, BanReason.BAN_REASON_OPERATIONAL);
            return BanStatusVO.builder()
                    .banned(true)
                    .reason(BanReason.BAN_REASON_OPERATIONAL.name())
                    .message("Account restricted")
                    .build();
        }

        // 3. DB regulation_status
        UserInfoEntity info = userInfoManager.findByUserId(userId);
        BanReason reason = UserBanManager.reasonFromRegulationStatus(
                info != null ? info.getRegulationStatus() : null);
        userBanManager.cacheBanReason(userId, reason);
        return toBanStatusVO(reason);
    }

    // ============ Helpers ============

    @Transactional(rollbackFor = Exception.class)
    protected Long createPlaceholder() {
        long userId = snowflakeIdGenerator.nextId();
        Instant now = Instant.now();
        UserInfoEntity entity = new UserInfoEntity();
        entity.setUserId(userId);
        entity.setNickname("User_" + userId);
        entity.setGender(0);
        entity.setRegulationStatus(0);
        entity.setPending(1);
        entity.setUserType(1);
        entity.setLastOpenAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userInfoManager.insertPlaceholder(entity);
        return userId;
    }

    private String normalizePhone(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new BizException(ErrorCode.PHONE_INVALID, "phone is blank");
        }
        try {
            PhoneNumber parsed = phoneNumberUtil.parse(rawPhone, null);
            if (!phoneNumberUtil.isValidNumber(parsed)) {
                throw new BizException(ErrorCode.PHONE_INVALID, "phone is not a valid E.164 number");
            }
            return phoneNumberUtil.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new BizException(ErrorCode.PHONE_INVALID, "phone parse failed: " + e.getMessage());
        }
    }

    private String requireAppName(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "appName is required");
        }
        return appName;
    }

    private Integer requirePlatform(Integer platform) {
        if (platform == null || platform <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "platform is required");
        }
        return platform;
    }

    private String requireNonBlank(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        return v;
    }

    private void tryLock(RLock lock) {
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "interrupted while acquiring lock");
        }
        if (!acquired) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "could not acquire register lock in 3s");
        }
    }

    private void safeUnlock(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("redisson unlock failed: {}", e.getMessage());
            }
        }
    }

    private BanStatusVO toBanStatusVO(BanReason reason) {
        boolean banned = reason != BanReason.BAN_REASON_NONE;
        String msg = switch (reason) {
            case BAN_REASON_USER_BANNED -> "Account banned";
            case BAN_REASON_USER_SUSPENDED -> "Account suspended";
            case BAN_REASON_OPERATIONAL -> "Account restricted";
            default -> "";
        };
        return BanStatusVO.builder()
                .banned(banned)
                .reason(reason.name())
                .bannedAtMs(0L)
                .message(banned ? msg : "")
                .build();
    }
}
