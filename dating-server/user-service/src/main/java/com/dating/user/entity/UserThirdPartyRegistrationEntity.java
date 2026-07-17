package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * UserThirdPartyRegistration Entity — 第三方账号 ↔ userId 绑定表.
 *
 * <p>数据库层 EXCLUDE 约束允许软删后重绑(同 platform+thirdPartyUserId+appName
 * 重复记录只能有一个 deleted=0).
 */
@Data
@TableName("user_third_party_registration")
public class UserThirdPartyRegistrationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1=Google 2=Facebook 3=Apple */
    private Integer platform;

    private String thirdPartyUserId;

    private String email;

    private String appName;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableLogic
    private Integer deleted;
}
