package com.dating.gateway.vo;

public class LoginResultVO {
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private Boolean pending;
    private Boolean newlyCreated;
    private Long accessExpiresAtMs;
    private Long refreshExpiresAtMs;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Boolean getPending() { return pending; }
    public void setPending(Boolean pending) { this.pending = pending; }
    public Boolean getNewlyCreated() { return newlyCreated; }
    public void setNewlyCreated(Boolean newlyCreated) { this.newlyCreated = newlyCreated; }
    public Long getAccessExpiresAtMs() { return accessExpiresAtMs; }
    public void setAccessExpiresAtMs(Long accessExpiresAtMs) { this.accessExpiresAtMs = accessExpiresAtMs; }
    public Long getRefreshExpiresAtMs() { return refreshExpiresAtMs; }
    public void setRefreshExpiresAtMs(Long refreshExpiresAtMs) { this.refreshExpiresAtMs = refreshExpiresAtMs; }
}
