package com.dating.gateway.service;

import com.dating.gateway.dto.LoginPhoneReq;
import com.dating.gateway.dto.LoginThirdPartyReq;
import com.dating.gateway.dto.OnboardingReq;
import com.dating.gateway.dto.RefreshTokenReq;
import com.dating.gateway.dto.SendSmsCodeReq;
import com.dating.gateway.vo.LoginResultVO;

public interface AuthService {
    void sendSmsCode(SendSmsCodeReq req);
    LoginResultVO loginPhone(LoginPhoneReq req);
    LoginResultVO loginDevice(String deviceId, Integer platform, String deviceModel, String osVersion, String appVersion, String pushToken);
    LoginResultVO loginThirdParty(LoginThirdPartyReq req);
    LoginResultVO refreshToken(RefreshTokenReq req);
    void logout(String refreshToken);
    void onboarding(Long userId, OnboardingReq req);
}
