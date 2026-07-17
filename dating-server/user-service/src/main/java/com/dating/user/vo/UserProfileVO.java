package com.dating.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * UserProfileVO — 单用户主资料 + 头像 + 兴趣 聚合 VO.
 *
 * <p>业务主键 {@code userId};数据库自增 {@code id} 不暴露.
 */
@Data
@Builder
public class UserProfileVO {

    private Long userId;
    private String nickname;
    private Integer age;
    private Integer gender;
    private LocalDate birthday;
    private String preferredLocation;
    private String bio;
    private String profession;     // UI 字段名 Occupation,DB profession,VO 一律 profession
    private String education;
    private Integer height;
    private String email;
    private Integer regulationStatus;
    private Boolean pending;
    private Instant lastOpenAt;
    private Long lastOpenAtMs;
    private Instant createdAt;
    private Instant updatedAt;

    /** 头像(MVP 三档同 key) */
    private AvatarVO avatar;

    /** 兴趣 */
    private java.util.List<UserInterestVO> interests;
}
