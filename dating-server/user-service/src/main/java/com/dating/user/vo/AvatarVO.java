package com.dating.user.vo;

import lombok.Builder;
import lombok.Data;

/**
 * AvatarVO — 仅承载 object_key,服务端不签 URL.
 *
 * <p>App 侧自拼 {@code ${cdnBaseUrl}/${bucket}/${key}} 拿到图片.
 */
@Data
@Builder
public class AvatarVO {

    private String originalKey;
    private String minKey;
    private String midKey;
}
