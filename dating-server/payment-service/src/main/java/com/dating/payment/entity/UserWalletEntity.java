package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户法币钱包实体.
 *
 * <p>对应 user_wallets 表，用于提现功能（当前未启用业务）.
 */
@Data
@TableName("user_wallets")
public class UserWalletEntity {

    /** 业务用户 ID，主键 */
    private Long userId;

    /** 可用余额，CHECK >= 0 */
    private BigDecimal balance;

    /** 冻结余额（提现中），CHECK >= 0 */
    private BigDecimal frozenBalance;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
