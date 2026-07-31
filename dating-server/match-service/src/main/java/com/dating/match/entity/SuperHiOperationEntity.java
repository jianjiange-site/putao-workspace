package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** Recoverable state spanning Redis quota, payment and local match transaction. */
@Data
@TableName("super_hi_operation")
public class SuperHiOperationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String operationKey;
    private Long userId;
    private Long targetUserId;
    private Integer targetUserType;
    private Integer subscriptionTier;
    private Integer coinsUsed;
    private String status;
    private Long matchId;
    private String lastError;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
    @TableLogic
    private Boolean deleted;
}
