package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * UserDeviceRegistration Entity — 设备 ↔ userId 绑定表(快速登录).
 *
 * <p>数据库层 EXCLUDE 约束允许软删后重绑(同 deviceId+platform+appName 重复记录
 * 只能有一个 deleted=0).
 */
@Data
@TableName("user_device_registration")
public class UserDeviceRegistrationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** iOS IDFV / Android SSAID / Web cookie */
    private String deviceId;

    /** 1=iOS / 2=Android / 3=Web */
    private Integer platform;

    private String appName;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Integer deleted;
}
