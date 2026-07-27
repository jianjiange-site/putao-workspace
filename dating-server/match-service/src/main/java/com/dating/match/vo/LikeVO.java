package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * LikeVO(对外屏蔽 from_user_type / source).
 */
@Data
public class LikeVO implements Serializable {

    private Long fromUserId;
    private String nickname;
    private Integer age;
    private List<String> photoKeys;
    private Long likedAtUnixMs;
    private String likeContent;
}