package com.dating.gateway.client;

import com.dating.payment.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PaymentClient {

    @Value("${payment.service.grpc.host:localhost}")
    private String paymentServiceHost;

    @Value("${payment.service.grpc.port:19093}")
    private int paymentServicePort;

    private PaymentServiceGrpc.PaymentServiceBlockingStub createStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(paymentServiceHost, paymentServicePort)
                .usePlaintext()
                .build();
        return PaymentServiceGrpc.newBlockingStub(channel);
    }

    public GetBalanceResponse getBalance(Long userId) {
        log.info("getBalance for userId={}", userId);
        GetBalanceRequest request = GetBalanceRequest.newBuilder().setUserId(userId).build();
        return createStub().getBalance(request);
    }

    public PurchaseCoinsResponse purchaseCoins(Long userId, int coinAmount, String paymentMethod) {
        log.info("purchaseCoins: userId={}, amount={}", userId, coinAmount);
        PurchaseCoinsRequest request = PurchaseCoinsRequest.newBuilder()
                .setUserId(userId).setCoinAmount(coinAmount).setPaymentMethod(paymentMethod).build();
        return createStub().purchaseCoins(request);
    }
}
