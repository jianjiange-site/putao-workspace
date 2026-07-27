package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * GetTodayFeed 响应 VO.
 */
@Data
public class GetTodayFeedRespVO implements Serializable {

    private List<CardVO> cards;
    private Boolean exhausted;
}