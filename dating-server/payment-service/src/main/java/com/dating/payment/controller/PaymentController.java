package com.dating.payment.controller;

import com.dating.payment.service.PaymentService;
import com.dating.payment.vo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 支付订单 REST Controller.
 */
@Slf4j
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 创建支付订单.
     *
     * @param userId    用户 ID
     * @param productId 商品 ID
     * @param channel   支付通道
     * @param returnUrl 跳转 URL
     */
    @PostMapping("/orders")
    public Result<CreateOrderVO> createOrder(
            @RequestParam Long userId,
            @RequestParam String productId,
            @RequestParam(defaultValue = "PAYPAL") String channel,
            @RequestParam(required = false) String returnUrl) {
        log.info("createOrder: userId={}, productId={}, channel={}", userId, productId, channel);
        CreateOrderVO vo = paymentService.createOrder(userId, productId, channel, returnUrl);
        return Result.ok(vo);
    }

    /**
     * 校验/确认支付.
     *
     * @param userId      用户 ID
     * @param orderId     业务订单号
     * @param extOrderId  第三方订单号
     */
    @PostMapping("/verify")
    public Result<VerifyPaymentVO> verifyPayment(
            @RequestParam Long userId,
            @RequestParam String orderId,
            @RequestParam(required = false) String extOrderId) {
        log.info("verifyPayment: userId={}, orderId={}", userId, orderId);
        VerifyPaymentVO vo = paymentService.verifyPayment(userId, orderId, extOrderId);
        return Result.ok(vo);
    }

    /**
     * 获取商品列表.
     */
    @GetMapping("/products")
    public Result<java.util.List<ProductVO>> getProducts() {
        return Result.ok(paymentService.getProducts());
    }

    /**
     * 获取订单详情.
     *
     * @param orderId 业务订单号
     */
    @GetMapping("/orders/{orderId}")
    public Result<PaymentOrderVO> getOrder(@PathVariable String orderId) {
        return Result.ok(paymentService.getOrder(orderId));
    }
}
