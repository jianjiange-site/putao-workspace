package com.dating.gateway.controller;

import com.dating.gateway.dto.*;
import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.AuthService;
import com.dating.gateway.vo.LoginResultVO;
import com.dating.gateway.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "Authentication APIs")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/sms/send")
    @Operation(summary = "Send SMS code")
    public Result<Void> sendSmsCode(@Valid @RequestBody SendSmsCodeReq req) {
        authService.sendSmsCode(req);
        return Result.ok();
    }

    @PostMapping("/login/phone")
    @Operation(summary = "Login with phone + SMS code")
    public Result<LoginResultVO> loginPhone(@Valid @RequestBody LoginPhoneReq req) {
        return Result.ok(authService.loginPhone(req));
    }

    @PostMapping("/login/device")
    @Operation(summary = "Login with device")
    public Result<LoginResultVO> loginDevice(@Valid @RequestBody LoginDeviceReq req) {
        return Result.ok(authService.loginDevice(
                req.getDeviceId(), req.getPlatform(), req.getDeviceModel(),
                req.getOsVersion(), req.getAppVersion(), req.getPushToken()));
    }

    @PostMapping("/login/third-party")
    @Operation(summary = "Login with third party (Google/Apple)")
    public Result<LoginResultVO> loginThirdParty(@Valid @RequestBody LoginThirdPartyReq req) {
        return Result.ok(authService.loginThirdParty(req));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh tokens")
    public Result<LoginResultVO> refreshToken(@Valid @RequestBody RefreshTokenReq req) {
        return Result.ok(authService.refreshToken(req));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout")
    public Result<Void> logout(@RequestBody LogoutReq req) {
        authService.logout(req.getRefreshToken());
        return Result.ok();
    }

    @PostMapping("/onboarding")
    @Operation(summary = "Complete user onboarding")
    public Result<Void> onboarding(@Valid @RequestBody OnboardingReq req) {
        Long userId = RequestContext.current().getUserId();
        authService.onboarding(userId, req);
        return Result.ok();
    }
}
