package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 提现记录实体.
 *
 * <p>对应 withdraw_records 表（当前业务逻辑未实现）.
 */
@Data
@TableName("withdraw_records")
public class WithdrawRecordEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务用户 ID */
    private Long userId;

    /** 提现单号，唯一 */
    private String withdrawNo;

    /** 申请金额（含手续费） */
    private BigDecimal amount;

    /** 手续费 */
    private BigDecimal fee;

    /** 到账金额 = amount - fee */
    private BigDecimal realAmount;

    /** 支付通道：PAYPAL, STRIPE, BANK */
    private String paymentChannel;

    /** 收款账户 */
    private String channelAccount;

    /** 状态：INIT, AUDITING, PROCESSING, SUCCESS, FAILED, REJECTED */
    private String status;

    /** 失败原因 */
    private String failReason;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
