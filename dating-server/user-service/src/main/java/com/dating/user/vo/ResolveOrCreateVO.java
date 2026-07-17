package com.dating.user.vo;

import lombok.Builder;
import lombok.Data;

/**
 * ResolveOrCreateVO — 身份解析结果.
 */
@Data
@Builder
public class ResolveOrCreateVO {

    private Long userId;
    private Boolean pending;
    private Boolean newlyCreated;
}