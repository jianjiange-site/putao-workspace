package com.dating.user.service;

import com.dating.user.dto.OnboardingDTO;
import com.dating.user.dto.UpdateProfileDTO;
import com.dating.user.vo.UserProfileVO;

import java.util.List;

/**
 * 用户资料域服务接口 — Profile / Interest / Avatar 一并在此.
 */
public interface UserProfileService {

    /**
     * 单用户读取,默认返回主资料 + 兴趣 + 头像.
     */
    UserProfileVO getProfile(Long userId);

    /**
     * 批量读取(≤200/次),Redis miss 后批量 DB 回填.
     *
     * @param includeInterests 是否附带兴趣(MVP 默认 true)
     * @return userId → VO 映射(去重后保序由调用方负责)
     */
    List<UserProfileVO> batchGetProfile(List<Long> userIds, boolean includeInterests);

    /**
     * 资料编辑(动态 SET,跳过 null 字段).
     */
    UserProfileVO updateProfile(Long callerUserId, UpdateProfileDTO dto);

    /**
     * onboarding 一次性写入完整资料 + 默认头像; gender / birthday 等注册期字段
     * 的唯一写入入口.
     */
    UserProfileVO upsertOnboarding(Long callerUserId, OnboardingDTO dto);
}
