package com.dating.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * PresignAvatarDTO — presign 请求参数.
 */
@Data
@Builder
public class PresignAvatarDTO {

    private Long userId;
    private String ext;       // 不含点号
    private Long sizeBytes;
}