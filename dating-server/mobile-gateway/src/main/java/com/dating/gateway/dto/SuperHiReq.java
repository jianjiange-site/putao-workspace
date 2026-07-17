package com.dating.gateway.dto;

public class SuperHiReq {
    private Long targetUserId;
    private String clientRequestId;

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long v) { this.targetUserId = v; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String v) { this.clientRequestId = v; }
}
