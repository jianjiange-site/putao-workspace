package com.dating.gateway.dto;

public class LoginThirdPartyReq {
    private Integer thirdPartyPlatform;
    private String idToken;
    private String googleEmail;
    private String deviceId;
    private Integer platform;
    private String deviceModel;
    private String osVersion;
    private String appVersion;
    private String pushToken;

    public Integer getThirdPartyPlatform() { return thirdPartyPlatform; }
    public void setThirdPartyPlatform(Integer v) { this.thirdPartyPlatform = v; }
    public String getIdToken() { return idToken; }
    public void setIdToken(String v) { this.idToken = v; }
    public String getGoogleEmail() { return googleEmail; }
    public void setGoogleEmail(String v) { this.googleEmail = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public Integer getPlatform() { return platform; }
    public void setPlatform(Integer v) { this.platform = v; }
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String v) { this.deviceModel = v; }
    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String v) { this.osVersion = v; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String v) { this.appVersion = v; }
    public String getPushToken() { return pushToken; }
    public void setPushToken(String v) { this.pushToken = v; }
}
