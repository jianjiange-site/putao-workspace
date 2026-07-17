package com.dating.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * ResolveOrCreateDTO — 身份解析请求.
 */
@Data
@Builder
public class ResolveOrCreateDTO {

    /** phone / thirdParty / device 互斥,由 service 层按渠道字段分发 */
    private String phoneE164;
    private Integer platform;            // 第三方 platform 或 device platform
    private String thirdPartyUserId;
    private String googleEmail;
    private String deviceId;
    private String appName;
}