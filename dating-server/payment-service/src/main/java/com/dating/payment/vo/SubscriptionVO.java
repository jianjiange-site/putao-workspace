package com.dating.payment.vo;

import lombok.Data;

/**
 * 订阅信息 VO.
 */
@Data
public class SubscriptionVO {
    private Long userId;
    private Integer tier;      // 1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY
    private boolean active;
    private long expiresAt;    // epoch ms, 0=无订阅
}
