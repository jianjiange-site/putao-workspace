package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * GetQuota 响应 VO.
 */
@Data
public class GetQuotaRespVO implements Serializable {

    private Integer dailyRightSwipeLimit;
    private Integer dailyRightSwipeUsed;
    private Integer dailyCardLimit;
    private Integer dailyCardUsed;
    private Integer dailySuperHiLimit;
    private Integer dailySuperHiUsed;
    private Integer superHiCoinPrice;
    private String subscriptionTier;
}