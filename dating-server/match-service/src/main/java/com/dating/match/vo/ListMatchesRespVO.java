package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * ListMatches 响应 VO.
 */
@Data
public class ListMatchesRespVO implements Serializable {

    private List<MatchVO> matches;
    private String nextPageToken;
}