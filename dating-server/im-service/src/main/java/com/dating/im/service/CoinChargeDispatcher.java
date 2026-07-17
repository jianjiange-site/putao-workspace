package com.dating.im.service;

import com.dating.im.client.PaymentServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 聊天扣费调度器.
 *
 * <p>异步执行真正的扣减操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinChargeDispatcher {

    private final PaymentServiceClient paymentClient;

    private final Executor chargeExecutor = Executors.newFixedThreadPool(2);

    /**
     * 异步扣费.
     *
     * @param userId    用户ID
     * @param amount    扣减数量
     * @param messageId 消息ID(幂等键)
     */
    public void dispatch(Long userId, int amount, String messageId) {
        chargeExecutor.execute(() -> {
            try {
                var result = paymentClient.consumeCoins(userId, amount, messageId);

                switch (result) {
                    case OK -> log.debug("Coin charged: userId={}, amount={}, messageId={}",
                            userId, amount, messageId);
                    case INSUFFICIENT -> log.warn("Insufficient coins during async charge: userId={}, messageId={}",
                            userId, messageId);
                    case FAILED -> log.error("Failed to charge coins: userId={}, amount={}, messageId={}",
                            userId, amount, messageId);
                }
            } catch (Exception e) {
                log.error("Exception during coin charge: userId={}, messageId={}", userId, messageId, e);
            }
        });
    }
}
