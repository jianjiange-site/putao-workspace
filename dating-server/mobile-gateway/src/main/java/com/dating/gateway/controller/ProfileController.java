package com.dating.gateway.controller;

import com.dating.gateway.dto.UpdateProfileReq;
import com.dating.gateway.security.RequestContext;
import com.dating.gateway.service.ProfileService;
import com.dating.gateway.vo.Result;
import com.dating.gateway.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Profile", description = "User Profile APIs")
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping("/me")
    @Operation(summary = "Get my profile")
    public Result<UserProfileVO> getMyProfile() {
        Long userId = RequestContext.current().getUserId();
        return Result.ok(profileService.getProfile(userId));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get user profile")
    public Result<UserProfileVO> getProfile(@PathVariable Long userId) {
        return Result.ok(profileService.getProfile(userId));
    }

    @PutMapping("/me")
    @Operation(summary = "Update my profile")
    public Result<UserProfileVO> updateProfile(@Valid @RequestBody UpdateProfileReq req) {
        Long userId = RequestContext.current().getUserId();
        return Result.ok(profileService.updateProfile(userId, req));
    }

    @GetMapping("/users")
    @Operation(summary = "Get multiple user profiles")
    public Result<List<UserProfileVO>> getUsers(@RequestParam List<Long> userIds) {
        Long userId = RequestContext.current().getUserId();
        return Result.ok(profileService.getUsers(userId, userIds));
    }
}
