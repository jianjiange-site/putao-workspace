package com.dating.im.service;

import com.dating.im.client.PaymentServiceClient;
import com.dating.im.exception.ImErrorCode;
import com.dating.im.model.ImEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Before-Send 处理器.
 *
 * <p>在消息发送前执行安检: 解析 senderId、DH 放行、反导流检测、扣费预检.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BeforeSendHandler {

    private final ContactInfoDetector contactInfoDetector;
    private final PaymentServiceClient paymentClient;
    private final CoinChargeDispatcher coinChargeDispatcher;

    @Value("${im.message.charge.enabled:true}")
    private boolean chargeEnabled;

    @Value("${im.message.charge.coin-cost:6}")
    private int coinCost;

    @Value("${im.message.charge.async:true}")
    private boolean chargeAsync;

    @Value("${im.message.anti-funnel.enabled:true}")
    private boolean antiFunnelEnabled;

    private final Executor chargeExecutor = Executors.newFixedThreadPool(2);

    /**
     * 处理 before-send 事件.
     *
     * @param event 消息发送前事件
     * @return 业务决策码: 0=允许,非0=拒绝
     */
    public int handle(ImEvent.MessageBeforeSendEvent event) {
        // 1. 解析 senderId 失败 -> 放行(不因解析问题误伤用户)
        if (event.getFromUserId() == null) {
            log.warn("Failed to parse senderId in beforeSend, allowing");
            return ImErrorCode.OK;
        }

        Long fromUserId = event.getFromUserId();
        Long toUserId = event.getToUserId();

        // 2. senderId 是 DH(数字人) -> 放行(AI 自己发的回复不安检不扣费)
        if (isDigitalHuman(fromUserId)) {
            log.debug("DH message, bypassing check: from={}", fromUserId);
            return ImErrorCode.OK;
        }

        // 3. 反导流检测
        if (antiFunnelEnabled && event.getMsgType() == 1) { // TEXT
            String content = event.getContent();
            String detected = contactInfoDetector.detect(content);
            if (detected != null) {
                log.info("Contact info detected: type={}, from={}, to={}", detected, fromUserId, toUserId);
                return ImErrorCode.REJECT_CONTACT_INFO;
            }
        }

        // 4. 扣费检测
        if (chargeEnabled) {
            return checkAndCharge(fromUserId, coinCost, event.getMessageId());
        }

        return ImErrorCode.OK;
    }

    private int checkAndCharge(Long userId, int cost, String messageId) {
        if (chargeAsync) {
            // 异步模式: 只做余额预检
            Long balance = paymentClient.getBalance(userId);
            if (balance == null) {
                log.warn("Failed to get balance for async charge, allowing", userId);
                return ImErrorCode.OK;
            }
            if (balance < cost) {
                log.info("Insufficient coins: userId={}, balance={}, cost={}", userId, balance, cost);
                return ImErrorCode.REJECT_INSUFFICIENT_COINS;
            }

            // 余额够,立即放行,异步扣减
            coinChargeDispatcher.dispatch(userId, cost, messageId);
            return ImErrorCode.OK;
        } else {
            // 同步模式: 直接扣减
            var result = paymentClient.consumeCoins(userId, cost, messageId);
            return switch (result) {
                case OK -> ImErrorCode.OK;
                case INSUFFICIENT -> ImErrorCode.REJECT_INSUFFICIENT_COINS;
                case FAILED -> ImErrorCode.REJECT_PAYMENT_UNAVAILABLE;
            };
        }
    }

    private boolean isDigitalHuman(Long userId) {
        // TODO: 从 UserServiceClient 获取
        return false;
    }
}
