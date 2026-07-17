package com.dating.gateway.service.impl;

import com.dating.gateway.client.UserClient;
import com.dating.gateway.dto.UpdateProfileReq;
import com.dating.gateway.service.ProfileService;
import com.dating.gateway.vo.UserProfileVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** Profile Service Implementation. */
@Service
public class ProfileServiceImpl implements ProfileService {

    private final UserClient userClient;

    public ProfileServiceImpl(UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public UserProfileVO getProfile(Long userId) {
        var profile = userClient.getUserProfile(userId);
        return UserProfileVO.builder()
                .userId(profile.getUserId())
                .nickname(profile.getNickname())
                .age(profile.getAge())
                .bio(profile.getBio())
                .avatar(profile.getAvatarKey())
                .build();
    }

    @Override
    public UserProfileVO updateProfile(Long userId, UpdateProfileReq req) {
        var profile = userClient.updateUserProfile(userId, req.getNickname(), req.getBio());
        return UserProfileVO.builder()
                .userId(userId)
                .nickname(profile.getNickname())
                .age(profile.getAge())
                .bio(profile.getBio())
                .avatar(profile.getAvatarKey())
                .build();
    }

    @Override
    public List<UserProfileVO> getUsers(Long currentUserId, List<Long> userIds) {
        return userClient.batchGetUserProfiles(userIds).stream()
                .map(p -> UserProfileVO.builder()
                        .userId(p.getUserId())
                        .nickname(p.getNickname())
                        .age(p.getAge())
                        .bio(p.getBio())
                        .avatar(p.getAvatarKey())
                        .build())
                .toList();
    }
}
