package com.dating.payment.service;

import com.dating.payment.vo.CreateOrderVO;
import com.dating.payment.vo.PaymentOrderVO;
import com.dating.payment.vo.VerifyPaymentVO;

/**
 * 支付服务接口.
 *
 * <p>定义支付下单的核心业务能力.
 */
public interface PaymentService {

    /**
     * 创建支付订单.
     *
     * @param userId    用户 ID
     * @param productId 商品 ID
     * @param channel   支付通道
     * @param returnUrl 跳转 URL（PayPal 用）
     * @return 创建结果
     */
    CreateOrderVO createOrder(Long userId, String productId, String channel, String returnUrl);

    /**
     * 校验/确认支付.
     *
     * @param userId      用户 ID
     * @param orderId     业务订单号
     * @param extOrderId  第三方订单号（PayPal）
     * @return 校验结果
     */
    VerifyPaymentVO verifyPayment(Long userId, String orderId, String extOrderId);

    /**
     * 处理 PayPal Webhook 回调.
     *
     * @param payload     回调 payload
     * @param eventType   事件类型
     * @param orderId     业务订单号
     * @param extOrderId  第三方订单号
     */
    void handlePayPalWebhook(String payload, String eventType, String orderId, String extOrderId);

    /**
     * 根据订单号查询订单.
     *
     * @param orderId 业务订单号
     * @return 订单 VO
     */
    PaymentOrderVO getOrder(String orderId);

    /**
     * 获取商品列表.
     *
     * @return 商品定义列表
     */
    java.util.List<com.dating.payment.vo.ProductVO> getProducts();
}
