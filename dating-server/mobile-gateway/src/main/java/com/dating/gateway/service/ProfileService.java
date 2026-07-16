package com.dating.gateway.service;

import com.dating.gateway.dto.UpdateProfileReq;
import com.dating.gateway.vo.UserProfileVO;

/** Profile Service Interface. */
public interface ProfileService {
    UserProfileVO getProfile(Long userId);
    UserProfileVO updateProfile(Long userId, UpdateProfileReq req);
}
