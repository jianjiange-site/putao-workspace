package com.dating.payment.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 提现历史记录 VO.
 */
@Data
@Accessors(chain = true)
public class WithdrawHistoryVO {
    private String withdrawNo;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal realAmount;
    private String channel;
    private String status;
    private String failReason;
    private long createdAt;
}
