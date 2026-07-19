package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * 金币账户实体.
 *
 * <p>对应 coin_accounts 表，记录用户金币余额（含免费币和付费币）.
 */
@Data
@TableName("coin_accounts")
public class CoinAccountEntity {

    /** 业务用户 ID，主键 */
    private Long userId;

    /** 免费金币余额，CHECK >= 0 */
    private Long balance;

    /** 付费金币余额（V4 新增），CHECK >= 0 */
    private Long paidBalance;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
