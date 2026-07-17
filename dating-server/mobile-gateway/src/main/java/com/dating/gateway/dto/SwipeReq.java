package com.dating.gateway.dto;

public class SwipeReq {
    private Long targetUserId;
    private String direction;

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long v) { this.targetUserId = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
}
