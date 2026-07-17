package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * UserInfo Entity — 用户主资料.
 *
 * <p>业务主键 {@code user_id}(雪花 ID,跨库稳定);{@code id} 为内部物理主键,不对外暴露.
 *
 * <p>对应表: user_info.
 */
@Data
@TableName("user_info")
public class UserInfoEntity {

    /** 内部物理主键,不对外暴露 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务主键: 雪花 ID,跨库稳定 */
    private Long userId;

    private String nickname;

    private Integer age;

    /** 0=未知 / 1=男 / 2=女 */
    private Integer gender;

    private LocalDate birthday;

    private String preferredLocation;

    private String bio;

    /** UI 字段名 Occupation;VO 层通过 MapStruct 转 occupation */
    private String profession;

    private String education;

    private Integer height;

    private String email;

    private String phoneNumber;

    private Long cityId;

    private java.math.BigDecimal lat;

    private java.math.BigDecimal lng;

    private Integer beautyScore;

    /** 1=Asian 2=Black 3=Latino 4=White 5=MiddleEast 6=Indian */
    private Integer race;

    /** 0=正常 / 2=Banned / 5=Suspended */
    private Integer regulationStatus;

    /** 1=placeholder(待 onboarding) / 0=已完成 */
    private Integer pending;

    /** 1=BH / 2=DH */
    private Integer userType;

    private Instant lastOpenAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
