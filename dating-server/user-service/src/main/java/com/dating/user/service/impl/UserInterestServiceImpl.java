package com.dating.user.service.impl;

import com.dating.user.converter.InterestConverter;
import com.dating.user.dto.InterestDTO;
import com.dating.user.entity.UserInterestEntity;
import com.dating.user.exception.BizException;
import com.dating.user.manager.UserInterestManager;
import com.dating.user.manager.UserProfileCacheManager;
import com.dating.user.service.UserInterestService;
import com.dating.user.vo.UserInterestVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UserInterest Service 实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInterestServiceImpl implements UserInterestService {

    private static final int MAX_PIC = 9;
    private static final int MAX_TEXT = 50;

    private final UserInterestManager userInterestManager;
    private final UserProfileCacheManager cacheManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int replaceUserInterests(Long userId, List<InterestDTO> dtos) {
        if (userId == null) {
            throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "userId required");
        }
        if (dtos == null || dtos.isEmpty()) {
            userInterestManager.deleteByUserId(userId);
            cacheManager.evictAll(userId);
            return 0;
        }

        // 1. 校验
        int picCount = 0;
        int textCount = 0;
        for (var d : dtos) {
            if (d == null) continue;
            if (d.getPicKey() != null && !d.getPicKey().isBlank()) picCount++;
            else textCount++;
        }
        if (picCount > MAX_PIC) {
            throw new BizException(com.dating.user.constant.ErrorCode.INTEREST_PIC_LIMIT_EXCEEDED);
        }
        if (textCount > MAX_TEXT) {
            throw new BizException(com.dating.user.constant.ErrorCode.INTEREST_TEXT_LIMIT_EXCEEDED);
        }

        // 2. 清 + 插
        userInterestManager.deleteByUserId(userId);
        int order = 0;
        for (var d : dtos) {
            if (d == null) continue;
            UserInterestEntity e = InterestConverter.INSTANCE.toEntity(d);
            e.setUserId(userId);
            e.setSortOrder(order++);
            userInterestManager.insertOne(e);
        }

        // 3. 清缓存
        cacheManager.evictAll(userId);
        log.info("replaceUserInterests userId={} count={}", userId, dtos.size());
        return dtos.size();
    }

    @Override
    public List<UserInterestVO> listByUserId(Long userId) {
        if (userId == null) return Collections.emptyList();
        return userInterestManager.listByUserId(userId).stream()
                .map(InterestConverter.INSTANCE::toVO)
                .collect(Collectors.toList());
    }
}