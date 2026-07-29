package com.dating.payment.service.impl;

import com.dating.payment.constant.SubscriptionTierConst;
import com.dating.payment.entity.UserSubscriptionEntity;
import com.dating.payment.exception.PaymentBizException;
import com.dating.payment.manager.SubscriptionCacheManager;
import com.dating.payment.manager.UserSubscriptionManager;
import com.dating.payment.service.SubscriptionService;
import com.dating.payment.vo.SubscriptionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 订阅服务实现.
 *
 * <p>支持 FREE/WEEKLY/MONTHLY/YEARLY 档位，到期判定，只升不降+时长顺延.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final UserSubscriptionManager subscriptionManager;
    private final SubscriptionCacheManager subscriptionCacheManager;

    @Override
    public SubscriptionVO getSubscription(Long userId) {
        // 1. 尝试从缓存获取
        SubscriptionVO cached = subscriptionCacheManager.getFromCache(userId);
        if (cached != null) {
            // 2. 缓存命中时，检查 expires_at 是否已过期（防止缓存 TTL > 订阅到期时间）
            if (isExpired(cached)) {
                log.debug("Subscription cache expired: userId={}, expiresAt={}", userId, cached.getExpiresAt());
                subscriptionCacheManager.evict(userId);
            } else {
                return cached;
            }
        }

        // 3. 缓存未命中或已过期，查询 DB
        SubscriptionVO vo = buildSubscriptionVO(userId);

        // 4. 回填缓存
        subscriptionCacheManager.putToCache(userId, vo);

        return vo;
    }

    /**
     * 判断订阅是否已过期.
     *
     * @param vo 订阅信息
     * @return true 表示已过期，需要删除缓存回源
     */
    private boolean isExpired(SubscriptionVO vo) {
        if (!vo.isActive()) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        return vo.getExpiresAt() > 0 && vo.getExpiresAt() < now;
    }

    /**
     * 构建订阅 VO(从 DB 查询).
     */
    private SubscriptionVO buildSubscriptionVO(Long userId) {
        SubscriptionVO vo = new SubscriptionVO();
        vo.setUserId(userId);
        vo.setTier(SubscriptionTierConst.FREE);
        vo.setActive(false);
        vo.setExpiresAt(0);

        Optional<UserSubscriptionEntity> optEntity = subscriptionManager.findActiveByUserId(userId);
        if (optEntity.isEmpty()) {
            return vo;
        }

        UserSubscriptionEntity entity = optEntity.get();
        Instant now = Instant.now();

        // 检查是否过期或 <= FREE
        if (entity.getExpiresAt() == null || entity.getExpiresAt().isBefore(now)
                || entity.getTier() <= SubscriptionTierConst.FREE) {
            vo.setTier(SubscriptionTierConst.FREE);
            vo.setActive(false);
            vo.setExpiresAt(0);
        } else {
            vo.setTier(entity.getTier());
            vo.setActive(true);
            vo.setExpiresAt(entity.getExpiresAt().toEpochMilli());
        }

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long activateSubscription(Long userId, int tier, int durationDays, String source) {
        // 不处理 FREE 档位
        if (tier <= SubscriptionTierConst.FREE) {
            log.debug("activateSubscription skip FREE tier: userId={}", userId);
            return 0;
        }

        Instant now = Instant.now();
        Optional<UserSubscriptionEntity> optEntity = subscriptionManager.findActiveByUserId(userId);

        UserSubscriptionEntity entity;
        if (optEntity.isEmpty()) {
            // 无记录 → INSERT
            entity = new UserSubscriptionEntity();
            entity.setUserId(userId);
            entity.setTier(tier);
            entity.setExpiresAt(now.plus(durationDays, ChronoUnit.DAYS));
            entity.setSource(source);
            entity.setDeleted(false);
            subscriptionManager.save(entity);
            log.info("activateSubscription new: userId={}, tier={}, expiresAt={}",
                    userId, tier, entity.getExpiresAt());
        } else {
            entity = optEntity.get();
            Instant currentExpires = entity.getExpiresAt();

            if (currentExpires != null && currentExpires.isAfter(now)) {
                // 未过期 → 档位只升不降，时长顺延
                if (tier > entity.getTier()) {
                    entity.setTier(tier);
                }
                entity.setExpiresAt(currentExpires.plus(durationDays, ChronoUnit.DAYS));
            } else {
                // 已过期 → 档位=新档位，从 now 重新算
                entity.setTier(tier);
                entity.setExpiresAt(now.plus(durationDays, ChronoUnit.DAYS));
            }
            entity.setSource(source);
            subscriptionManager.updateById(entity);
            log.info("activateSubscription renew: userId={}, tier={}, expiresAt={}",
                    userId, entity.getTier(), entity.getExpiresAt());
        }

        // 缓存失效，下次查询会回源
        subscriptionCacheManager.evict(userId);

        return entity.getExpiresAt().toEpochMilli();
    }
}
