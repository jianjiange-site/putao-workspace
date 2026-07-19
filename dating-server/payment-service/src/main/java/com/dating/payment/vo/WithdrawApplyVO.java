package com.dating.payment.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 提现申请结果 VO.
 */
@Data
@Accessors(chain = true)
public class WithdrawApplyVO {
    private String withdrawNo;
    private String status;
    private BigDecimal amount;
    private BigDecimal realAmount;
    private String message;
}
