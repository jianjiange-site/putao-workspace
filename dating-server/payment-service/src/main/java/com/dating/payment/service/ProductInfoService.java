package com.dating.payment.service;

import com.dating.payment.vo.ProductVO;

import java.util.List;

/**
 * 商品信息服务接口.
 *
 * <p>提供商品定义和价格查询.
 */
public interface ProductInfoService {

    /**
     * 获取所有商品列表.
     *
     * @return 商品列表
     */
    List<ProductVO> getAllProducts();

    /**
     * 根据商品 ID 获取商品信息.
     *
     * @param productId 商品 ID
     * @return 商品信息，不存在返回 null
     */
    ProductVO getProduct(String productId);

    /**
     * 检查是否为订阅商品.
     *
     * @param productId 商品 ID
     * @return 是否为订阅商品
     */
    boolean isSubscriptionProduct(String productId);

    /**
     * 获取订阅档位（仅订阅商品有效）.
     *
     * @param productId 商品 ID
     * @return 档位值（1=FREE 2=WEEKLY 3=MONTHLY 4=YEARLY）
     */
    int getSubscriptionTier(String productId);

    /**
     * 获取订阅天数（仅订阅商品有效）.
     *
     * @param productId 商品 ID
     * @return 天数
     */
    int getSubscriptionDays(String productId);
}
