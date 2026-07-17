package com.dating.gateway.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Payment Service gRPC Client. Stubbed — payment-service not yet implemented. */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    @Value("${payment.service.grpc.host:localhost}")
    private String paymentServiceHost;

    @Value("${payment.service.grpc.port:19093}")
    private int paymentServicePort;

    // TODO: implement once payment-service is ready
    public record GetBalanceResponse(Long userId, Long coins) {}
    public record PurchaseCoinsResponse(String orderId, Long coins, Long createdAt) {}

    public GetBalanceResponse getBalance(Long userId) {
        log.warn("PaymentClient.getBalance not implemented — returning empty response");
        return new GetBalanceResponse(userId, 0L);
    }

    public PurchaseCoinsResponse purchaseCoins(Long userId, int coinAmount, String paymentMethod) {
        log.warn("PaymentClient.purchaseCoins not implemented — returning empty response");
        return new PurchaseCoinsResponse("", 0L, 0L);
    }
}
