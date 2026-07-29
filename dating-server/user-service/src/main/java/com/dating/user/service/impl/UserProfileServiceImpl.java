package com.dating.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.user.converter.InterestConverter;
import com.dating.user.converter.UserProfileConverter;
import com.dating.user.dto.OnboardingDTO;
import com.dating.user.dto.UpdateProfileDTO;
import com.dating.user.entity.UserInfoEntity;
import com.dating.user.entity.UserInterestEntity;
import com.dating.user.exception.BizException;
import com.dating.user.exception.UserNotFoundException;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.manager.UserInterestManager;
import com.dating.user.manager.UserProfileCacheManager;
import com.dating.user.service.UserProfileService;
import com.dating.user.vo.UserInterestVO;
import com.dating.user.vo.UserProfileVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserProfile Service 实现 — Profile 域(资料 + 兴趣).
 *
 * <p>写后一律 evict profile:* / interest:* 缓存;读路径走 cache aside.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    /** 批量上限(设计文档 §6.4) */
    private static final int BATCH_LIMIT = 200;

    private final UserInfoManager userInfoManager;
    private final UserInterestManager userInterestManager;
    private final UserProfileCacheManager cacheManager;
    private final ObjectMapper objectMapper;

    @Override
    public UserProfileVO getProfile(Long userId) {
        // 1. 尝试从缓存获取基础资料（不包含 interests）
        String cached = cacheManager.getProfileJson(userId);
        if (cached != null) {
            UserProfileVO cachedVo = cacheManager.readJson(cached, UserProfileVO.class);
            if (cachedVo != null) {
                log.debug("getProfile cache hit: userId={}", userId);
                // 2. 兴趣走独立缓存（单独更新，不影响 profile 缓存）
                List<UserInterestEntity> interests = loadInterests(userId);
                cachedVo.setInterests(interests.stream()
                        .map(InterestConverter.INSTANCE::toVO)
                        .collect(Collectors.toList()));
                return cachedVo;
            }
        }

        // 3. 缓存未命中，查询 DB
        UserInfoEntity entity = userInfoManager.findByUserId(userId);
        if (entity == null) {
            throw new UserNotFoundException(userId);
        }
        UserProfileVO vo = UserProfileConverter.INSTANCE.toVO(entity);

        // 4. 回填基础资料缓存（不含 interests，避免 interests 变更时失效 profile 缓存）
        try {
            cacheManager.cacheProfileJson(userId, objectMapper.writeValueAsString(vo));
        } catch (Exception e) {
            log.warn("cacheProfileJson failed: userId={}, err={}", userId, e.getMessage());
        }

        // 5. 兴趣(走 interest cache)
        List<UserInterestEntity> interests = loadInterests(userId);
        vo.setInterests(interests.stream()
                .map(InterestConverter.INSTANCE::toVO)
                .collect(Collectors.toList()));

        // 6. 头像:设计文档 §5.4 提到 user_info.custom_avatar JSONB 列,
        //    V1 表里未建,留接口位置后续 V2 加列(V2__add_user_avatar_column.sql)
        vo.setAvatar(null);
        return vo;
    }

    @Override
    public List<UserProfileVO> batchGetProfile(List<Long> userIds, boolean includeInterests) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyList();

        // 1. 去重 + 校验上限
        List<Long> unique = userIds.stream().distinct().collect(Collectors.toList());
        if (unique.size() > BATCH_LIMIT) {
            throw new BizException(
                    com.dating.user.constant.ErrorCode.BATCH_TOO_LARGE,
                    "batch size " + unique.size() + " exceeds limit " + BATCH_LIMIT);
        }

        // 2. 先查缓存，收集未命中
        List<Long> cacheMisses = new ArrayList<>();
        Map<Long, UserProfileVO> cachedMap = new java.util.LinkedHashMap<>();
        for (Long uid : unique) {
            String cached = cacheManager.getProfileJson(uid);
            if (cached != null) {
                UserProfileVO cachedVo = cacheManager.readJson(cached, UserProfileVO.class);
                if (cachedVo != null) {
                    log.debug("batchGetProfile cache hit: userId={}", uid);
                    cachedMap.put(uid, cachedVo);
                    continue;
                }
            }
            cacheMisses.add(uid);
        }

        // 3. 缓存未命中，批量查 DB
        Map<Long, UserInfoEntity> userMap = Collections.emptyMap();
        if (!cacheMisses.isEmpty()) {
            userMap = userInfoManager.mapByUserIds(cacheMisses);
            // 回填缓存
            for (UserInfoEntity entity : userMap.values()) {
                try {
                    UserProfileVO vo = UserProfileConverter.INSTANCE.toVO(entity);
                    cacheManager.cacheProfileJson(entity.getUserId(), objectMapper.writeValueAsString(vo));
                    cachedMap.put(entity.getUserId(), vo);
                } catch (Exception e) {
                    log.warn("batch cacheProfileJson failed: userId={}, err={}", entity.getUserId(), e.getMessage());
                }
            }
        }

        // 4. 兴趣(走独立缓存)
        Map<Long, List<UserInterestEntity>> interestMap = Collections.emptyMap();
        if (includeInterests) {
            interestMap = userInterestManager.mapByUserIds(unique);
        }

        // 5. 按入参顺序组装
        List<UserProfileVO> result = new ArrayList<>(unique.size());
        for (Long uid : unique) {
            UserProfileVO vo = cachedMap.get(uid);
            if (vo == null) continue;
            if (includeInterests) {
                List<UserInterestEntity> items = interestMap.getOrDefault(uid, Collections.emptyList());
                vo.setInterests(items.stream().map(InterestConverter.INSTANCE::toVO).collect(Collectors.toList()));
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO updateProfile(Long callerUserId, UpdateProfileDTO dto) {
        UserInfoEntity existing = userInfoManager.findByUserId(callerUserId);
        if (existing == null) {
            throw new UserNotFoundException(callerUserId);
        }

        // 1. 动态 SET,只更新非 null 字段
        UserInfoEntity patch = new UserInfoEntity();

        boolean changed = false;
        if (StringUtils.hasText(dto.getNickname())) {
            validateNickname(dto.getNickname());
            patch.setNickname(dto.getNickname().trim());
            changed = true;
        }
        if (dto.getAge() != null) {
            if (dto.getAge() < 0 || dto.getAge() > 150) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "age out of range");
            }
            patch.setAge(dto.getAge());
            changed = true;
        }
        if (dto.getPreferredLocation() != null) {
            if (dto.getPreferredLocation().length() > 128) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "preferredLocation too long");
            }
            patch.setPreferredLocation(dto.getPreferredLocation());
            changed = true;
        }
        if (dto.getBio() != null) {
            if (dto.getBio().length() > 500) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "bio too long (≤500)");
            }
            patch.setBio(dto.getBio());
            changed = true;
        }
        if (dto.getOccupation() != null) {
            if (dto.getOccupation().length() > 128) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "occupation too long");
            }
            patch.setProfession(dto.getOccupation());     // VO occupation → DB profession
            changed = true;
        }
        if (dto.getEducation() != null) {
            if (dto.getEducation().length() > 128) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "education too long");
            }
            patch.setEducation(dto.getEducation());
            changed = true;
        }
        if (dto.getHeight() != null) {
            if (dto.getHeight() < 0 || dto.getHeight() > 300) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "height out of range");
            }
            patch.setHeight(dto.getHeight());
            changed = true;
        }

        if (!changed) {
            return getProfile(callerUserId);
        }

        // 2. 按主键更新
        LambdaUpdateWrapper<UserInfoEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserInfoEntity::getId, existing.getId());
        userInfoManager.updateSelective(patch, wrapper);

        // 3. evict cache
        cacheManager.evictAll(callerUserId);

        log.info("updateProfile userId={} changed=true", callerUserId);
        return getProfile(callerUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO upsertOnboarding(Long callerUserId, OnboardingDTO dto) {
        UserInfoEntity existing = userInfoManager.findByUserId(callerUserId);
        if (existing == null) {
            throw new UserNotFoundException(callerUserId);
        }

        // 1. 校验必填
        if (!StringUtils.hasText(dto.getNickname())) {
            throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "nickname required");
        }
        validateNickname(dto.getNickname());
        if (dto.getGender() == null || dto.getGender() < 0 || dto.getGender() > 2) {
            throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "gender required (0/1/2)");
        }
        if (dto.getBirthday() == null) {
            throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "birthday required");
        }

        // 2. 全量覆盖 onboarding 字段(包含 gender / birthday / pending)
        UserInfoEntity patch = new UserInfoEntity();
        patch.setNickname(dto.getNickname().trim());
        patch.setGender(dto.getGender());
        patch.setBirthday(dto.getBirthday());
        patch.setPending(0);
        if (dto.getPreferredLocation() != null) patch.setPreferredLocation(dto.getPreferredLocation());
        if (dto.getBio() != null) {
            if (dto.getBio().length() > 500) {
                throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "bio too long");
            }
            patch.setBio(dto.getBio());
        }
        if (dto.getOccupation() != null) patch.setProfession(dto.getOccupation());
        if (dto.getEducation() != null) patch.setEducation(dto.getEducation());
        if (dto.getHeight() != null) patch.setHeight(dto.getHeight());

        LambdaUpdateWrapper<UserInfoEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserInfoEntity::getId, existing.getId());
        userInfoManager.updateSelective(patch, wrapper);

        // 3. 写兴趣
        if (dto.getInterests() != null && !dto.getInterests().isEmpty()) {
            replaceInterests(callerUserId, dto.getInterests());
        }

        cacheManager.evictAll(callerUserId);
        log.info("upsertOnboarding userId={} gender={} birthday={}", callerUserId, dto.getGender(), dto.getBirthday());
        return getProfile(callerUserId);
    }

    // ============ Helpers ============

    private List<UserInterestEntity> loadInterests(Long userId) {
        String cached = cacheManager.getInterestsJson(userId);
        if (cached != null) {
            try {
                List<UserInterestEntity> entities = objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, UserInterestEntity.class));
                if (entities != null) return entities;
            } catch (Exception e) {
                log.warn("interest cache deserialize failed, fallthrough to DB: {}", e.getMessage());
            }
        }
        List<UserInterestEntity> entities = userInterestManager.listByUserId(userId);
        try {
            cacheManager.cacheInterestsJson(userId, objectMapper.writeValueAsString(entities));
        } catch (Exception e) {
            log.warn("interest cache write failed: {}", e.getMessage());
        }
        return entities;
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceInterests(Long userId, List<com.dating.user.dto.InterestDTO> dtos) {
        int picCount = 0;
        int textCount = 0;
        for (var d : dtos) {
            if (d.getPicKey() != null && !d.getPicKey().isBlank()) picCount++;
            else textCount++;
        }
        if (picCount > 9) {
            throw new BizException(com.dating.user.constant.ErrorCode.INTEREST_PIC_LIMIT_EXCEEDED);
        }
        if (textCount > 50) {
            throw new BizException(com.dating.user.constant.ErrorCode.INTEREST_TEXT_LIMIT_EXCEEDED);
        }
        userInterestManager.deleteByUserId(userId);
        int order = 0;
        for (var d : dtos) {
            UserInterestEntity e = InterestConverter.INSTANCE.toEntity(d);
            e.setUserId(userId);
            e.setSortOrder(order++);
            userInterestManager.insertOne(e);
        }
        cacheManager.evictAll(userId);
    }

    private void validateNickname(String nickname) {
        if (nickname.length() > 64) {
            throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "nickname too long (≤64)");
        }
    }
}
