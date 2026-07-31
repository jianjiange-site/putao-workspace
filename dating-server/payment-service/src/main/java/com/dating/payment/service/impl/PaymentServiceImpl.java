package com.dating.payment.service.impl;

import com.dating.payment.constant.OrderStatus;
import com.dating.payment.constant.PaymentChannel;
import com.dating.payment.constant.PaymentErrorCode;
import com.dating.payment.entity.PaymentOrderEntity;
import com.dating.payment.exception.PaymentBizException;
import com.dating.payment.executor.PayPalCaptureResult;
import com.dating.payment.executor.PaypalExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dating.payment.manager.PaymentOrderManager;
import com.dating.payment.service.CoinService;
import com.dating.payment.service.PaymentService;
import com.dating.payment.service.ProductInfoService;
import com.dating.payment.service.SubscriptionService;
import com.dating.payment.vo.CreateOrderVO;
import com.dating.payment.vo.PaymentOrderVO;
import com.dating.payment.vo.ProductVO;
import com.dating.payment.vo.VerifyPaymentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 支付服务实现.
 *
 * <p>完整实现 PayPal 支付链路：下单 → 回调/capture → 发奖.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentOrderManager orderManager;
    private final CoinService coinService;
    private final SubscriptionService subscriptionService;
    private final ProductInfoService productInfoService;
    private final PaypalExecutor paypalExecutor;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateOrderVO createOrder(Long userId, String productId, String channel, String returnUrl) {
        // 1. 检查商品
        ProductVO product = productInfoService.getProduct(productId);
        if (product == null) {
            throw new PaymentBizException(PaymentErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId);
        }

        // 2. 生成内部订单号
        String orderId = "P" + System.currentTimeMillis() + String.format("%06d", (int) (Math.random() * 999999));

        // 3. PayPal 通道处理
        if (PaymentChannel.PAYPAL.equals(channel)) {
            return createPayPalOrder(userId, orderId, product, returnUrl);
        }

        // 4. 其他通道暂不支持
        throw new PaymentBizException(PaymentErrorCode.NOT_IMPLEMENTED,
                "Channel not supported: " + channel);
    }

    private CreateOrderVO createPayPalOrder(Long userId, String orderId, ProductVO product, String returnUrl) {
        try {
            // 1. 调用 PayPal 创建订单
            String extOrderId;
            String checkoutUrl;
            try {
                var paypalResult = paypalExecutor.createOrder(
                        orderId,
                        product.getPrice(),
                        product.getName(),
                        returnUrl
                );
                extOrderId = paypalResult.extOrderId();
                checkoutUrl = paypalResult.checkoutUrl();
            } catch (Exception e) {
                log.error("PayPal createOrder failed: orderId={}", orderId, e);
                throw new PaymentBizException(PaymentErrorCode.PAYPAL_CREATE_ORDER_FAILED, e.getMessage());
            }

            // 2. 保存订单
            PaymentOrderEntity entity = new PaymentOrderEntity();
            entity.setOrderId(orderId);
            entity.setUserId(userId);
            entity.setProductId(product.getProductId());
            entity.setAmount(product.getPrice());
            entity.setCurrency("USD");
            entity.setPaymentChannel(PaymentChannel.PAYPAL);
            entity.setStatus(OrderStatus.INIT);
            entity.setRefundStatus("NONE");
            entity.setRefundedAmount(BigDecimal.ZERO);
            entity.setExtTransactionId(extOrderId);
            entity.setNotifyStatus("PENDING");
            entity.setNotifyCount(0);
            entity.setReturnUrl(returnUrl);
            orderManager.save(entity);

            // 3. 返回结果
            CreateOrderVO vo = new CreateOrderVO();
            vo.setOrderId(orderId);
            vo.setStatus(OrderStatus.INIT);
            vo.setExtOrderId(extOrderId);
            vo.setCheckoutUrl(checkoutUrl);
            return vo;

        } catch (PaymentBizException e) {
            throw e;
        } catch (Exception e) {
            log.error("createOrder failed: userId={}, productId={}", userId, product.getProductId(), e);
            throw new PaymentBizException(500, "Create order failed: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VerifyPaymentVO verifyPayment(Long userId, String orderId, String extOrderId) {
        PaymentOrderEntity order = orderManager.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND));
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND);
        }

        if (OrderStatus.PAID.equals(order.getStatus())) {
            grantReward(orderId, "PayPal");
        } else if (!OrderStatus.GRANTED.equals(order.getStatus())
                && PaymentChannel.PAYPAL.equals(order.getPaymentChannel())) {
            try {
                PayPalCaptureResult captured =
                        paypalExecutor.captureOrder(order.getExtTransactionId());
                validateCapture(order, captured);
                advanceToPaid(orderId);
                grantReward(orderId, "PayPal");
            } catch (PaymentBizException e) {
                if (e.getCode() == PaymentErrorCode.PAYPAL_CAPTURE_FAILED) {
                    advanceToFailed(orderId, e.getMessage());
                }
                throw e;
            }
        }

        PaymentOrderEntity current = orderManager.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND));
        VerifyPaymentVO vo = new VerifyPaymentVO();
        vo.setOrderId(orderId);
        vo.setStatus(current.getStatus());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePayPalWebhook(String payload, String eventType, String orderId, String extOrderId) {
        log.info("handlePayPalWebhook: eventType={}, orderId={}", eventType, orderId);

        PaymentOrderEntity located = orderId == null
                ? orderManager.findByExternalTransaction(PaymentChannel.PAYPAL, extOrderId)
                    .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND))
                : orderManager.findByOrderId(orderId)
                    .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND));
        PaymentOrderEntity order = orderManager.findByOrderIdForUpdate(located.getOrderId())
                .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND));
        validateWebhookOrder(payload, order, extOrderId);

        switch (eventType) {
            case "PAYMENT.CAPTURE.COMPLETED" -> {
                if (!OrderStatus.GRANTED.equals(order.getStatus())) {
                    advanceToPaid(order.getOrderId());
                    grantReward(order.getOrderId(), "PayPal");
                }
            }
            case "CHECKOUT.ORDER.APPROVED" -> {
                try {
                    PayPalCaptureResult captured =
                            paypalExecutor.captureOrder(order.getExtTransactionId());
                    validateCapture(order, captured);
                    advanceToPaid(order.getOrderId());
                    grantReward(order.getOrderId(), "PayPal");
                } catch (Exception e) {
                    log.warn("Auto capture failed: extOrderId={}",
                            order.getExtTransactionId(), e);
                }
            }
            default -> log.debug("Unhandled PayPal event: {}", eventType);
        }
    }

    @Override
    public PaymentOrderVO getOrder(String orderId) {
        PaymentOrderEntity entity = orderManager.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentBizException(PaymentErrorCode.ORDER_NOT_FOUND));

        PaymentOrderVO vo = new PaymentOrderVO();
        vo.setOrderId(entity.getOrderId());
        vo.setUserId(entity.getUserId());
        vo.setProductId(entity.getProductId());
        vo.setAmount(entity.getAmount());
        vo.setCurrency(entity.getCurrency());
        vo.setChannel(entity.getPaymentChannel());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt().toEpochMilli());
        return vo;
    }

    @Override
    public List<ProductVO> getProducts() {
        return productInfoService.getAllProducts();
    }

    /**
     * 推进订单状态到 PAID.
     */
    private void advanceToPaid(String orderId) {
        int updated = orderManager.updateStatus(
                orderId, OrderStatus.PAID, OrderStatus.INIT, OrderStatus.FAILED);
        if (updated > 0) {
            log.info("Order advanced to PAID: orderId={}", orderId);
        }
    }

    /**
     * 推进订单状态到 FAILED.
     */
    private void advanceToFailed(String orderId, String reason) {
        orderManager.updateStatus(orderId, OrderStatus.FAILED, OrderStatus.INIT);
        log.warn("Order advanced to FAILED: orderId={}, reason={}", orderId, reason);
    }

    /**
     * 发放奖励（幂等：GRANTED 状态直接跳过）.
     */
    private void grantReward(String orderId, String source) {
        PaymentOrderEntity order = orderManager.findByOrderIdForUpdate(orderId).orElse(null);
        if (order == null) {
            log.warn("grantReward order not found: {}", orderId);
            return;
        }

        if (OrderStatus.GRANTED.equals(order.getStatus())) {
            log.info("grantReward already granted: orderId={}", orderId);
            return;
        }
        if (!OrderStatus.PAID.equals(order.getStatus())) {
            throw new PaymentBizException(500, "Order is not paid: " + orderId);
        }

        // 获取商品
        ProductVO product = productInfoService.getProduct(order.getProductId());
        if (product == null) {
            throw new PaymentBizException(PaymentErrorCode.PRODUCT_NOT_FOUND,
                    "Product not found: " + order.getProductId());
        }

        Long userId = order.getUserId();

        // 1. 订阅商品：先激活/续期订阅
        if (productInfoService.isSubscriptionProduct(product.getProductId())) {
            // 获取订阅等级
            int tier = product.getSubscriptionTier();
            int days = product.getSubscriptionDays();
            // 激活订阅
            subscriptionService.activateSubscription(userId, tier, days, source);
        }

        // 2. 发付费金币
        // 添加付费金币
        coinService.addPaidCoins(userId, product.getCoins(),
                // 描述
                "Purchase: " + product.getName(),
                // 订单 ID
                "order:" + orderId);

        // 3. 标记 GRANTED
        int granted = orderManager.updateStatus(orderId, OrderStatus.GRANTED, OrderStatus.PAID);
        if (granted != 1) {
            throw new PaymentBizException(500, "Failed to finalize reward: " + orderId);
        }

        log.info("grantReward completed: orderId={}, userId={}, coins={}",
                orderId, userId, product.getCoins());
    }

    private void validateCapture(PaymentOrderEntity order, PayPalCaptureResult capture) {
        if (!order.getExtTransactionId().equals(capture.extOrderId())) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "External order id mismatch");
        }
        if (!order.getOrderId().equals(capture.merchantOrderId())) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Merchant order id mismatch");
        }
        if (order.getAmount().compareTo(capture.amount()) != 0) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Captured amount mismatch");
        }
        if (!order.getCurrency().equalsIgnoreCase(capture.currency())) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Captured currency mismatch");
        }
    }

    private void validateWebhookOrder(
            String payload, PaymentOrderEntity order, String extOrderId) {
        try {
            if (extOrderId != null && !extOrderId.equals(order.getExtTransactionId())) {
                throw new PaymentBizException(PaymentErrorCode.PAYPAL_WEBHOOK_HANDLER_FAILED,
                        "Webhook external order id mismatch");
            }
            JsonNode resource = objectMapper.readTree(payload).path("resource");
            String merchantOrderId = resource.path("custom_id").asText("");
            if (!merchantOrderId.isBlank() && !merchantOrderId.equals(order.getOrderId())) {
                throw new PaymentBizException(PaymentErrorCode.PAYPAL_WEBHOOK_HANDLER_FAILED,
                        "Webhook merchant order id mismatch");
            }
            JsonNode amount = resource.path("amount");
            if (!amount.isMissingNode() && amount.hasNonNull("value")) {
                BigDecimal paidAmount = new BigDecimal(amount.path("value").asText());
                String currency = amount.path("currency_code").asText();
                if (order.getAmount().compareTo(paidAmount) != 0
                        || !order.getCurrency().equalsIgnoreCase(currency)) {
                    throw new PaymentBizException(PaymentErrorCode.PAYPAL_WEBHOOK_HANDLER_FAILED,
                            "Webhook amount or currency mismatch");
                }
            }
        } catch (PaymentBizException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_WEBHOOK_HANDLER_FAILED,
                    "Invalid PayPal webhook payload");
        }
    }
}
