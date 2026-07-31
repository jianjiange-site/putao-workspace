package com.dating.payment.grpc;

import com.dating.payment.proto.*;
import com.dating.payment.service.CoinService;
import com.dating.payment.service.PaymentService;
import com.dating.payment.service.SubscriptionService;
import com.dating.payment.vo.CoinAccountVO;
import com.dating.payment.vo.SubscriptionVO;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Payment gRPC 服务实现.
 *
 * <p>实现 proto/payment/payment.proto 中定义的所有 RPC 接口.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGrpcService {

    private final PaymentService paymentService;
    private final CoinService coinService;
    private final SubscriptionService subscriptionService;

    // ===== Payment =====

    /**
     * 创建支付订单.
     */
    public void createOrder(CreateOrderRequest request,
                          StreamObserver<CreateOrderResponse> responseObserver) {
        try {
            var vo = paymentService.createOrder(
                    request.getUserId(),
                    request.getProductId(),
                    toChannelString(request.getChannel()),
                    request.getReturnUrl()
            );

            CreateOrderResponse response = CreateOrderResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setOrderId(vo.getOrderId())
                    .setStatus(vo.getStatus())
                    .setExtOrderId(vo.getExtOrderId() != null ? vo.getExtOrderId() : "")
                    .setCheckoutUrl(vo.getCheckoutUrl() != null ? vo.getCheckoutUrl() : "")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("createOrder failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    /**
     * 校验/确认支付.
     */
    public void verifyPayment(VerifyPaymentRequest request,
                             StreamObserver<VerifyPaymentResponse> responseObserver) {
        try {
            var vo = paymentService.verifyPayment(
                    request.getUserId(),
                    request.getOrderId(),
                    request.getExtOrderId()
            );

            VerifyPaymentResponse response = VerifyPaymentResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setOrderId(vo.getOrderId())
                    .setStatus(vo.getStatus())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("verifyPayment failed: userId={}, orderId={}",
                    request.getUserId(), request.getOrderId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    /**
     * 查询余额（兼容旧 API，返回 free+paid 之和）.
     */
    public void getBalance(GetBalanceRequest request,
                         StreamObserver<GetBalanceResponse> responseObserver) {
        try {
            CoinAccountVO vo = coinService.getCoins(request.getUserId());

            GetBalanceResponse response = GetBalanceResponse.newBuilder()
                    .setUserId(request.getUserId())
                    .setCoins(vo.getTotalBalance())
                    .setFreeCoins(vo.getBalance())
                    .setPaidCoins(vo.getPaidBalance())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("getBalance failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    // ===== Coins =====

    /**
     * 查询金币.
     */
    public void getCoins(GetBalanceRequest request,
                        StreamObserver<GetBalanceResponse> responseObserver) {
        getBalance(request, responseObserver);
    }

    /**
     * 增加免费金币.
     */
    public void addCoins(AddCoinsRequest request,
                        StreamObserver<AddCoinsResponse> responseObserver) {
        try {
            long balanceAfter = coinService.addCoins(
                    request.getUserId(),
                    request.getAmount(),
                    request.getReason(),
                    request.getIdempotentKey()
            );

            AddCoinsResponse response = AddCoinsResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setBalanceAfter(balanceAfter)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("addCoins failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    /**
     * 增加付费金币.
     */
    public void addPaidCoins(AddPaidCoinsRequest request,
                            StreamObserver<AddPaidCoinsResponse> responseObserver) {
        try {
            long paidBalanceAfter = coinService.addPaidCoins(
                    request.getUserId(),
                    request.getAmount(),
                    request.getReason(),
                    request.getIdempotentKey()
            );

            AddPaidCoinsResponse response = AddPaidCoinsResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setPaidBalanceAfter(paidBalanceAfter)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("addPaidCoins failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    /**
     * 扣减金币（先扣免费再扣付费，幂等）.
     */
    public void consumeCoins(ConsumeCoinsRequest request,
                           StreamObserver<ConsumeCoinsResponse> responseObserver) {
        try {
            // 兼容旧字段名
            String idempotencyKey = !request.getIdempotentKey().isEmpty()
                    ? request.getIdempotentKey()
                    : request.getIdempotentKey();

            CoinService.ConsumeResult result = coinService.consumeCoins(
                    request.getUserId(),
                    request.getAmount(),
                    idempotencyKey,
                    request.getDescription()
            );

            ConsumeCoinsResponse response = ConsumeCoinsResponse.newBuilder()
                    .setSuccess(result.success())
                    .setCode(result.code())
                    .setMessage(result.message())
                    .setBalanceAfter(result.balanceAfter())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("consumeCoins failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    /**
     * 查询金币流水.
     */
    public void getCoinLedger(GetCoinLedgerRequest request,
                              StreamObserver<GetCoinLedgerResponse> responseObserver) {
        try {
            int page = request.getPage() > 0 ? request.getPage() : 1;
            int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;

            // TODO: 实现分页查询
            GetCoinLedgerResponse response = GetCoinLedgerResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setPage(page)
                    .setPageSize(pageSize)
                    .setTotal(0)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("getCoinLedger failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    // ===== Subscription =====

    /**
     * 查询订阅.
     */
    public void getSubscription(GetSubscriptionRequest request,
                               StreamObserver<GetSubscriptionResponse> responseObserver) {
        try {
            SubscriptionVO vo = subscriptionService.getSubscription(request.getUserId());

            GetSubscriptionResponse response = GetSubscriptionResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setTier(toSubscriptionTierProto(vo.getTier()))
                    .setIsActive(vo.isActive())
                    .setExpiresAt(vo.getExpiresAt())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("getSubscription failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    /**
     * 激活订阅.
     */
    public void activateSubscription(ActivateSubscriptionRequest request,
                                    StreamObserver<ActivateSubscriptionResponse> responseObserver) {
        try {
            long expiresAt = subscriptionService.activateSubscription(
                    request.getUserId(),
                    request.getTier().getNumber(),
                    request.getDurationDays(),
                    toSourceString(request.getSource())
            );

            ActivateSubscriptionResponse response = ActivateSubscriptionResponse.newBuilder()
                    .setCode(0)
                    .setMessage("OK")
                    .setTier(request.getTier())
                    .setExpiresAt(expiresAt)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("activateSubscription failed: userId={}", request.getUserId(), e);
            sendError(responseObserver, 500, e.getMessage());
        }
    }

    // ===== Purchase (legacy) =====

    /**
     * 购买金币（遗留接口）.
     */
    public void purchaseCoins(PurchaseCoinsRequest request,
                             StreamObserver<PurchaseCoinsResponse> responseObserver) {
        // 遗留接口，简化实现
        PurchaseCoinsResponse response = PurchaseCoinsResponse.newBuilder()
                .setOrderId("")
                .setCoins(0)
                .setCreatedAt(0)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // ===== Helpers =====

    private String toChannelString(PaymentChannel channel) {
        return switch (channel) {
            case PAYPAL -> "PAYPAL";
            case APPLE_IAP -> "APPLE_IAP";
            case GOOGLE_BILLING -> "GOOGLE_BILLING";
            case STRIPE -> "STRIPE";
            default -> "";
        };
    }

    private SubscriptionTier toSubscriptionTierProto(int tier) {
        return switch (tier) {
            case 1 -> SubscriptionTier.FREE;
            case 2 -> SubscriptionTier.WEEKLY;
            case 3 -> SubscriptionTier.MONTHLY;
            case 4 -> SubscriptionTier.YEARLY;
            default -> SubscriptionTier.SUBSCRIPTION_TIER_UNSPECIFIED;
        };
    }

    private String toSourceString(SubscriptionSource source) {
        return switch (source) {
            case IAP_APPLE -> "IAP_APPLE";
            case IAP_GOOGLE -> "IAP_GOOGLE";
            case SRC_PAYPAL -> "PAYPAL";
            case SRC_STRIPE -> "STRIPE";
            case ADMIN -> "ADMIN";
            case TEST -> "TEST";
            default -> "";
        };
    }

    private <T> void sendError(StreamObserver<T> observer, int code, String message) {
        String safeMessage = message == null || message.isBlank()
                ? "Payment service request failed" : message;
        observer.onError(Status.INTERNAL.withDescription(safeMessage).asRuntimeException());
    }
}
