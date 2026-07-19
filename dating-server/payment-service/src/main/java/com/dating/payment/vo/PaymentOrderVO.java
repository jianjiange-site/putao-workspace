package com.dating.payment.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付订单 VO.
 */
@Data
public class PaymentOrderVO {
    private String orderId;
    private Long userId;
    private String productId;
    private BigDecimal amount;
    private String currency;
    private String channel;
    private String status;
    private long createdAt;
}
