package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * 金币流水实体.
 *
 * <p>对应 coin_ledger 表，append-only 审计日志.
 */
@Data
@TableName("coin_ledger")
public class CoinLedgerEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务用户 ID */
    private Long userId;

    /** 类型：INCOME, EXPENSE */
    private String type;

    /** 免费币变动量 */
    private Long amount;

    /** 变动后免费余额 */
    private Long balanceAfter;

    /** 付费币变动量（V4 新增） */
    private Long paidAmount;

    /** 变动后付费余额（V4 新增） */
    private Long paidBalanceAfter;

    /** 变动原因 */
    private String reason;

    /** 扩展数据 JSON */
    private String extra;

    /** 幂等键（V6 新增），部分唯一索引 */
    private String idempotencyKey;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
