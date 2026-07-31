package com.dating.payment.executor;

import java.math.BigDecimal;

/**
 * PayPal capture/order verification result.
 */
public record PayPalCaptureResult(
        String extOrderId,
        String merchantOrderId,
        BigDecimal amount,
        String currency,
        String merchantId
) {
}
