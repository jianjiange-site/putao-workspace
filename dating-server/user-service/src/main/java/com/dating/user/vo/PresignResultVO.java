package com.dating.user.vo;

import lombok.Builder;
import lombok.Data;

/**
 * PresignResultVO — 头像上传预签名结果.
 */
@Data
@Builder
public class PresignResultVO {

    private String presignedUrl;
    private String objectKey;
    private Long expiresAtMs;
}
