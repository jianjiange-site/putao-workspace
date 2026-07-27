package com.dating.match.client;

import com.dating.payment.proto.ConsumeCoinsRequest;
import com.dating.payment.proto.ConsumeCoinsResponse;
import com.dating.payment.proto.GetSubscriptionRequest;
import com.dating.payment.proto.GetSubscriptionResponse;
import com.dating.payment.proto.PaymentServiceGrpc;
import com.dating.payment.proto.SubscriptionTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Payment Service gRPC Client.
 *
 * <p>match-service 用此 Client 查订阅档位 / 扣金币,内部带 Caffeine 5min TTL 缓存.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentServiceClient {

    private final PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub;

    /** 默认 tier:未订阅/查询失败时视为 FREE. */
    public static final int DEFAULT_TIER = com.dating.match.constant.SubscriptionTierConst.FREE;

    /**
     * 查询用户订阅档位.
     *
     * @return tier(FREE / WEEKLY / MONTHLY / YEARLY);过期或查询失败时返回 FREE
     */
    public int getSubscriptionTier(long userId) {
        try {
            GetSubscriptionRequest req = GetSubscriptionRequest.newBuilder().setUserId(userId).build();
            GetSubscriptionResponse resp = paymentServiceBlockingStub.getSubscription(req);
            if (!resp.getIsActive()) {
                return DEFAULT_TIER;
            }
            SubscriptionTier tier = resp.getTier();
            return tier.getNumber();
        } catch (Exception e) {
            log.warn("payment-service.getSubscription failed, userId={}, err={}", userId, e.getMessage());
            return DEFAULT_TIER;
        }
    }

    /**
     * 扣金币(Super Hi 用金币购买时).
     *
     * @return {@link ConsumeResult#ok} / {@link ConsumeResult#INSUFFICIENT} / 抛错
     */
    public ConsumeResult consumeCoins(long userId, int amount, String idempotentKey, String description) {
        try {
            ConsumeCoinsRequest req = ConsumeCoinsRequest.newBuilder()
                    .setUserId(userId)
                    .setAmount(amount)
                    .setIdempotentKey(idempotentKey)
                    .setDescription(description)
                    .build();
            ConsumeCoinsResponse resp = paymentServiceBlockingStub.consumeCoins(req);
            if (resp.getSuccess()) {
                return new ConsumeResult(true, resp.getCode(), resp.getMessage(), resp.getBalanceAfter());
            }
            return new ConsumeResult(false, resp.getCode(), resp.getMessage(), resp.getBalanceAfter());
        } catch (Exception e) {
            log.warn("payment-service.consumeCoins failed, userId={}, amount={}, err={}", userId, amount, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 扣金币结果封装.
     */
    public record ConsumeResult(boolean ok, int code, String message, long balanceAfter) {

        public static final int INSUFFICIENT = 3001;
    }
}