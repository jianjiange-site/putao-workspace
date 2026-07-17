package com.dating.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * OnboardingDTO — onboarding 一次性写入请求.
 */
@Data
@Builder
public class OnboardingDTO {

    /** caller userId(metadata x-user-id 注入) */
    private Long userId;
    private String nickname;
    private Integer gender;            // 必填
    private LocalDate birthday;        // 必填
    private String preferredLocation;
    private String bio;
    private String occupation;
    private String education;
    private Integer height;
    private List<InterestDTO> interests;
}