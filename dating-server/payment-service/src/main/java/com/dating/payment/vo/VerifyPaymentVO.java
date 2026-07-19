package com.dating.payment.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 校验支付 VO.
 */
@Data
@Accessors(chain = true)
public class VerifyPaymentVO {
    private String orderId;
    private String status;
}
