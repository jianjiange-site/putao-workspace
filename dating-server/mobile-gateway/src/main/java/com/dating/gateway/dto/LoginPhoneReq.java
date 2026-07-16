package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class LoginPhoneReq {
    @NotBlank(message = "Phone number is required")
    private String phone;

    @NotBlank(message = "SMS code is required")
    private String smsCode;

    @NotBlank(message = "Device ID is required")
    private String deviceId;

    @NotNull(message = "Platform is required")
    private Integer platform;

    private String deviceModel;
    private String osVersion;
    private String appVersion;
    private String pushToken;
}
