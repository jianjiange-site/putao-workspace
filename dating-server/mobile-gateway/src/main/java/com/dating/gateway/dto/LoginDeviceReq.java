package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class LoginDeviceReq {
    @NotBlank(message = "Device ID is required")
    private String deviceId;

    @NotNull(message = "Platform is required")
    private Integer platform;

    private String deviceModel;
    private String osVersion;
    private String appVersion;
    private String pushToken;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public Integer getPlatform() { return platform; }
    public void setPlatform(Integer platform) { this.platform = platform; }
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String osVersion) { this.osVersion = osVersion; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getPushToken() { return pushToken; }
    public void setPushToken(String pushToken) { this.pushToken = pushToken; }
}
