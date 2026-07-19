package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 钱包流水实体.
 *
 * <p>对应 user_wallet_entries 表，append-only 审计日志.
 */
@Data
@TableName("user_wallet_entries")
public class WalletEntryEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务用户 ID */
    private Long userId;

    /** 类型：INCOME, WITHDRAW_FREEZE, WITHDRAW_SUCCESS, WITHDRAW_FAIL, ADMIN_ADJUST */
    private String entryType;

    /** 变动金额 */
    private BigDecimal amount;

    /** 变动前余额 */
    private BigDecimal beforeBalance;

    /** 变动后余额 */
    private BigDecimal afterBalance;

    /** 关联订单号 */
    private String orderId;

    /** 关联提现单号 */
    private String withdrawNo;

    /** 变动原因 */
    private String reason;

    /** 扩展数据 JSON */
    private String extra;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
