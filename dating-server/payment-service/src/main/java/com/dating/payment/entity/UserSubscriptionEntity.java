package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * 用户订阅实体.
 *
 * <p>对应 user_subscription 表.
 */
@Data
@TableName("user_subscription")
public class UserSubscriptionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务用户 ID */
    private Long userId;

    /** 档位：1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY */
    private Integer tier;

    /** 到期时间，NULL 或 < now() 视为过期 */
    private Instant expiresAt;

    /** 来源：IAP_APPLE, IAP_GOOGLE, TEST, ADMIN */
    private String source;

    /** 软删标记 */
    @TableLogic
    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
