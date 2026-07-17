package com.dating.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * UpdateProfileDTO — UpdateProfile RPC 内部 DTO.
 *
 * <p>只承载 7 个 MVP 字段;nullable 字段表示"不修改"(动态 SET).
 */
@Data
@Builder
public class UpdateProfileDTO {

    /** caller userId(metadata x-user-id 注入),用于 manager 写库 */
    private Long userId;
    private String nickname;
    private Integer age;
    private String preferredLocation;
    private String bio;
    private String occupation;   // UI 字段名,DB 存 profession
    private String education;
    private Integer height;
}
