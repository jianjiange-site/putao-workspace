package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * ListLikesOfMe 响应 VO.
 */
@Data
public class ListLikesOfMeRespVO implements Serializable {

    private List<LikeVO> likes;
    private String nextPageToken;
    private Integer totalUnread;
}