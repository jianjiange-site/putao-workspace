package com.dating.match.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Super Hi 响应 VO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SuperHiRespVO implements Serializable {

    /** 命中 match 的 ID(>0 即匹配成功). */
    private Long matchId;

    /** 用了金币数量(0 = 订阅赠送). */
    private Integer coinsUsed;

    /** 是否幂等回放. */
    private Boolean idempotent;
}