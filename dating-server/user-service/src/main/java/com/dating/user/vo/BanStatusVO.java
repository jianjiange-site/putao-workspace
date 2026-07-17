package com.dating.user.vo;

import lombok.Builder;
import lombok.Data;

/**
 * BanStatusVO — CheckBan 响应 VO.
 */
@Data
@Builder
public class BanStatusVO {

    private Boolean banned;
    private String reason;          // USER_BANNED / USER_SUSPENDED / OPERATIONAL / NONE
    private Long bannedAtMs;
    private String message;
}