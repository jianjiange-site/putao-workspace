package com.dating.payment.service.impl;

import com.dating.payment.constant.SubscriptionTierConst;
import com.dating.payment.service.ProductInfoService;
import com.dating.payment.vo.ProductVO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品信息服务实现.
 *
 * <p>硬编码商品定义，支持金币充值和订阅商品.
 */
@Slf4j
@Service
public class ProductInfoServiceImpl implements ProductInfoService {

    /** 商品定义 Map */
    private final Map<String, ProductVO> products = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // 金币充值商品（按分定价，1分=100金币）
        addProduct("1", "100 Coins", 99, 100, 0, null);
        addProduct("2", "550 Coins", 499, 550, 0, null);
        addProduct("3", "1150 Coins", 999, 1150, 0, null);
        addProduct("4", "2400 Coins", 1999, 2400, 0, null);
        addProduct("5", "6250 Coins", 4999, 6250, 0, null);
        addProduct("6", "13000 Coins", 9999, 13000, 0, null);

        // 订阅商品（发付费金币 + 激活订阅档位）
        addProduct("sub-weekly", "Weekly Subscription", 999, 1000,
                SubscriptionTierConst.WEEKLY, 7);
        addProduct("sub-monthly", "Monthly Subscription", 2999, 3000,
                SubscriptionTierConst.MONTHLY, 30);
        addProduct("sub-yearly", "Yearly Subscription", 7999, 8000,
                SubscriptionTierConst.YEARLY, 365);

        log.info("ProductInfoService initialized with {} products", products.size());
    }

    private void addProduct(String productId, String name, int priceCent,
                            int coins, int tier, Integer days) {
        ProductVO vo = new ProductVO();
        vo.setProductId(productId);
        vo.setName(name);
        vo.setPrice(new BigDecimal(priceCent).divide(new BigDecimal(100)));
        vo.setPriceCent(priceCent);
        vo.setCoins(coins);
        vo.setSubscriptionTier(tier);
        vo.setSubscriptionDays(days);
        products.put(productId, vo);
    }

    @Override
    public List<ProductVO> getAllProducts() {
        return products.values().stream().toList();
    }

    @Override
    public ProductVO getProduct(String productId) {
        return products.get(productId);
    }

    @Override
    public boolean isSubscriptionProduct(String productId) {
        ProductVO vo = products.get(productId);
        return vo != null && vo.getSubscriptionTier() > 0;
    }

    @Override
    public int getSubscriptionTier(String productId) {
        ProductVO vo = products.get(productId);
        return vo != null ? vo.getSubscriptionTier() : 0;
    }

    @Override
    public int getSubscriptionDays(String productId) {
        ProductVO vo = products.get(productId);
        return vo != null && vo.getSubscriptionDays() != null ? vo.getSubscriptionDays() : 0;
    }
}
