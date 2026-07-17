package com.dating.user.manager;

import com.dating.user.entity.UserThirdPartyRegistrationEntity;
import com.dating.user.mapper.UserThirdPartyRegistrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * UserThirdPartyRegistration Manager — 第三方绑定表读写.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserThirdPartyManager {

    private final UserThirdPartyRegistrationMapper userThirdPartyRegistrationMapper;

    public UserThirdPartyRegistrationEntity findActive(Integer platform,
                                                       String thirdPartyUserId,
                                                       String appName) {
        if (platform == null || thirdPartyUserId == null || appName == null) return null;
        return userThirdPartyRegistrationMapper.findActive(platform, thirdPartyUserId, appName);
    }

    public void insert(Long userId, Integer platform, String thirdPartyUserId, String appName, String email) {
        UserThirdPartyRegistrationEntity entity = new UserThirdPartyRegistrationEntity();
        entity.setUserId(userId);
        entity.setPlatform(platform);
        entity.setThirdPartyUserId(thirdPartyUserId);
        entity.setEmail(email);
        entity.setAppName(appName);
        userThirdPartyRegistrationMapper.insert(entity);
    }
}
