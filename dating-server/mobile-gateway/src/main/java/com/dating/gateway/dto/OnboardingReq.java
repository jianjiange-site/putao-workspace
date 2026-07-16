package com.dating.gateway.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class OnboardingReq {
    @NotBlank(message = "Nickname is required")
    @Size(min = 1, max = 50)
    private String nickname;

    @NotNull
    @Min(0) @Max(2)
    private Integer gender;

    @NotBlank
    private String birthday;

    private Integer age;
    private Integer height;
    private String bio;
    private String occupation;
    private String education;
    private String location;
    private String defaultAvatarObjectKey;
}
