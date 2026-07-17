package com.dating.user.vo;

import lombok.Builder;
import lombok.Data;

/**
 * UserInterestVO — 单条兴趣标签.
 */
@Data
@Builder
public class UserInterestVO {

    private Long id;
    private String tabKey;
    private String tagKey;
    private String picKey;
    private Integer sortOrder;
}
