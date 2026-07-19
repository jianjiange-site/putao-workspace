package com.dating.payment.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 创建订单 VO.
 */
@Data
@Accessors(chain = true)
public class CreateOrderVO {
    private String orderId;         // 业务订单号
    private String status;          // INIT/PAID/GRANTED/FAILED
    private String extOrderId;      // PayPal order id
    private String checkoutUrl;     // PayPal approval link
}
