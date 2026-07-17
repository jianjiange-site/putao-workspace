package com.dating.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * ConfirmAvatarDTO — confirm 头像上传请求参数.
 */
@Data
@Builder
public class ConfirmAvatarDTO {

    private Long userId;
    private String objectKey;
}
