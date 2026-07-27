package com.dating.match.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Swipe 响应 VO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SwipeRespVO implements Serializable {

    /** 命中 match 的 ID(>0 即匹配成功).0 表示未触发 match. */
    private Long matchId;

    /** 是否幂等回放. */
    private Boolean idempotent;
}