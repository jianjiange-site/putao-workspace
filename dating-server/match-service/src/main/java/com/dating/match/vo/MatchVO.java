package com.dating.match.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * MatchVO 单条.
 */
@Data
public class MatchVO implements Serializable {

    private Long matchId;
    private Long partnerUserId;
    private String partnerNickname;
    private List<String> partnerPhotoKeys;
    private Long matchedAtUnixMs;
    private String source;
}