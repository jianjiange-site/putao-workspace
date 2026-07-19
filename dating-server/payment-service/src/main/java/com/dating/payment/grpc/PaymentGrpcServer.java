package com.dating.payment.grpc;

import com.dating.payment.proto.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Payment gRPC Server 实现.
 *
 * <p>实现 PaymentServiceGrpc 接口，代理到 PaymentGrpcService.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class PaymentGrpcServer extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final PaymentGrpcService grpcService;

    @Override
    public void createOrder(CreateOrderRequest request,
                           StreamObserver<CreateOrderResponse> responseObserver) {
        grpcService.createOrder(request, responseObserver);
    }

    @Override
    public void verifyPayment(VerifyPaymentRequest request,
                              StreamObserver<VerifyPaymentResponse> responseObserver) {
        grpcService.verifyPayment(request, responseObserver);
    }

    @Override
    public void getBalance(GetBalanceRequest request,
                          StreamObserver<GetBalanceResponse> responseObserver) {
        grpcService.getBalance(request, responseObserver);
    }

    @Override
    public void purchaseCoins(PurchaseCoinsRequest request,
                             StreamObserver<PurchaseCoinsResponse> responseObserver) {
        grpcService.purchaseCoins(request, responseObserver);
    }

    @Override
    public void getCoins(GetBalanceRequest request,
                        StreamObserver<GetBalanceResponse> responseObserver) {
        grpcService.getCoins(request, responseObserver);
    }

    @Override
    public void addCoins(AddCoinsRequest request,
                        StreamObserver<AddCoinsResponse> responseObserver) {
        grpcService.addCoins(request, responseObserver);
    }

    @Override
    public void addPaidCoins(AddPaidCoinsRequest request,
                            StreamObserver<AddPaidCoinsResponse> responseObserver) {
        grpcService.addPaidCoins(request, responseObserver);
    }

    @Override
    public void consumeCoins(ConsumeCoinsRequest request,
                            StreamObserver<ConsumeCoinsResponse> responseObserver) {
        grpcService.consumeCoins(request, responseObserver);
    }

    @Override
    public void getCoinLedger(GetCoinLedgerRequest request,
                             StreamObserver<GetCoinLedgerResponse> responseObserver) {
        grpcService.getCoinLedger(request, responseObserver);
    }

    @Override
    public void getSubscription(GetSubscriptionRequest request,
                              StreamObserver<GetSubscriptionResponse> responseObserver) {
        grpcService.getSubscription(request, responseObserver);
    }

    @Override
    public void activateSubscription(ActivateSubscriptionRequest request,
                                    StreamObserver<ActivateSubscriptionResponse> responseObserver) {
        grpcService.activateSubscription(request, responseObserver);
    }
}
