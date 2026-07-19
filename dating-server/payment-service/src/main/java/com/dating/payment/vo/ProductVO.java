package com.dating.payment.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 商品信息 VO.
 */
@Data
@Accessors(chain = true)
public class ProductVO {
    private String productId;
    private String name;
    private BigDecimal price;       // 价格（元）
    private int priceCent;          // 价格（分）
    private int coins;              // 赠送金币数量
    private int subscriptionTier;   // 订阅档位（0=非订阅）
    private Integer subscriptionDays;// 订阅天数
}
