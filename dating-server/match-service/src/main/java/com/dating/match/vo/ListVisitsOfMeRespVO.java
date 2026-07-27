package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * ListVisitsOfMe 响应 VO.
 */
@Data
public class ListVisitsOfMeRespVO implements Serializable {

    private List<VisitVO> visits;
    private String nextPageToken;
    private Integer totalUnread;
}