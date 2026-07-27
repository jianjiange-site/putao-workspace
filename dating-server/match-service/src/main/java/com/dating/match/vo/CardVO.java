package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 卡片 VO(GetTodayFeed 单条).
 */
@Data
public class CardVO implements Serializable {

    private Long targetUserId;
    private Integer targetUserType;
    private String nickname;
    private Integer age;
    private List<String> photoKeys;
    private String bio;
    private Double distanceKm;
}