package com.dating.payment.executor;

import lombok.experimental.Accessors;

/**
 * PayPal 订单创建结果.
 */
@Accessors(chain = true)
public record PayPalOrderResult(
        String extOrderId,   // PayPal order id
        String checkoutUrl   // approval link
) {
}
