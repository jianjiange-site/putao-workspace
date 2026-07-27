package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * VisitVO(对外屏蔽 from_user_type / source).
 */
@Data
public class VisitVO implements Serializable {

    private Long fromUserId;
    private String nickname;
    private Integer age;
    private List<String> photoKeys;
    private Long visitedAtUnixMs;
    private Integer visitCount;
}