package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * UserLoginPhone Entity — 手机号 ↔ userId 绑定表.
 *
 * <p>唯一约束 {@code (phone_e164, app_name)} 由数据库 ENFORCE;无软删(物理唯一).
 */
@Data
@TableName("user_login_phone")
public class UserLoginPhoneEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** libphonenumber 规范化后的 E.164(例: +8613800138000) */
    private String phoneE164;

    /** 应用名,例: vibe / chatvibe */
    private String appName;

    private Instant verifiedAt;

    private Instant createdAt;
}
