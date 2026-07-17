package com.dating.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * InterestDTO — 兴趣标签内部 DTO.
 */
@Data
@Builder
public class InterestDTO {

    private String tabKey;
    private String tagKey;
    private String picKey;
    private Integer sortOrder;
}