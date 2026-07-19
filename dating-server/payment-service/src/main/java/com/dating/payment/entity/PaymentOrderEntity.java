package com.dating.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付订单实体.
 *
 * <p>对应 payment_orders 表，记录充值下单全流程.
 */
@Data
@TableName("payment_orders")
public class PaymentOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务用户 ID */
    private Long userId;

    /** 业务订单号，唯一索引 */
    private String orderId;

    /** 内部商品 id / Apple ProductID */
    private String productId;

    /** 金额（元） */
    private BigDecimal amount;

    /** 币种，默认 USD */
    private String currency;

    /** 支付通道：APPLE_IAP, GOOGLE_BILLING, PAYPAL, STRIPE */
    private String paymentChannel;

    /** 状态：INIT, PAID, GRANTED, FAILED */
    private String status;

    /** 退款状态：NONE, PARTIAL, FULL */
    private String refundStatus;

    /** 已退款金额 */
    private BigDecimal refundedAmount;

    /** 第三方交易号（PayPal order id） */
    private String extTransactionId;

    /** 回调通知状态：PENDING, NOTIFIED, CONFIRMED, FAILED */
    private String notifyStatus;

    /** 回调通知次数 */
    private Integer notifyCount;

    /** 最后回调时间 */
    private Instant notifyLastAt;

    /** 跳转回跳地址 */
    private String returnUrl;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
