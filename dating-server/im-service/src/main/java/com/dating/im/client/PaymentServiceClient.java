package com.dating.im.client;

import com.dating.payment.proto.GetBalanceRequest;
import com.dating.payment.proto.GetBalanceResponse;
import com.dating.payment.proto.ConsumeCoinsRequest;
import com.dating.payment.proto.ConsumeCoinsResponse;
import com.dating.payment.proto.PaymentServiceGrpc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Payment Service Client.
 *
 * <p>调用 payment-service gRPC 接口进行聊天扣费.
 */
@Slf4j
@Component
public class PaymentServiceClient {

    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    /** 读操作超时(ms) */
    private static final int READ_TIMEOUT_MS = 800;

    /** 写操作超时(ms) */
    private static final int WRITE_TIMEOUT_MS = 2000;

    public PaymentServiceClient(PaymentServiceGrpc.PaymentServiceBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * 获取用户金币余额.
     *
     * @param userId 用户ID
     * @return 余额, 失败返回 null
     */
    public Long getBalance(Long userId) {
        try {
            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            GetBalanceResponse response = stub.getBalance(request);
            return response.getCoins();
        } catch (Exception e) {
            log.warn("Failed to get balance, userId={}", userId, e);
            return null;
        }
    }

    /**
     * 消费金币.
     *
     * @param userId   用户ID
     * @param amount   数量
     * @param messageId 幂等键
     * @return 消费结果
     */
    public ChargeResult consumeCoins(Long userId, int amount, String messageId) {
        try {
            ConsumeCoinsRequest request = ConsumeCoinsRequest.newBuilder()
                    .setUserId(userId)
                    .setAmount(amount)
                    .setIdempotentKey("im-msg:" + messageId)
                    .setDescription("Chat message charge")
                    .build();
            ConsumeCoinsResponse response = stub.consumeCoins(request);

            if (response.getSuccess()) {
                return ChargeResult.OK;
            } else {
                int code = response.getCode();
                if (code == 3001) {
                    return ChargeResult.INSUFFICIENT;
                }
                return ChargeResult.FAILED;
            }
        } catch (Exception e) {
            log.error("Failed to consume coins, userId={}, amount={}", userId, amount, e);
            return ChargeResult.FAILED;
        }
    }

    /** 消费结果 */
    public enum ChargeResult {
        OK,
        INSUFFICIENT,
        FAILED
    }
}
