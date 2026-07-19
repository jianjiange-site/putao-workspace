package com.dating.payment.vo;

import lombok.Data;

/**
 * 金币账户 VO.
 */
@Data
public class CoinAccountVO {
    private Long userId;
    private Long balance;        // 免费币余额
    private Long paidBalance;    // 付费币余额
    private Long totalBalance;   // 总余额
}
