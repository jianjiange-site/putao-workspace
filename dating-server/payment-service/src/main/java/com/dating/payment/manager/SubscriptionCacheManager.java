package com.dating.payment.manager;

import com.dating.payment.constant.PaymentRedisKey;
import com.dating.payment.vo.SubscriptionVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 订阅缓存管理器.
 *
 * <p>采用 Cache-Aside 模式：读时检查缓存，缓存未命中回源 DB 并回填；写时删除缓存.
 * <p>TTL 设置为 24 小时（配合写失效策略，订阅变更时主动删除缓存）。
 *    由于订阅变更（买/续/取消）是低频事件，写失效能保证一致性，
 *    长 TTL 可以在缓存命中时大量减少 DB 查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionCacheManager {

    private static final long SUBSCRIPTION_CACHE_TTL_HOURS = 24;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 从缓存获取订阅信息.
     *
     * @param userId 用户 ID
     * @return 缓存的 SubscriptionVO，未命中返回 null
     */
    public SubscriptionVO getFromCache(Long userId) {
        String key = PaymentRedisKey.subscription(userId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.debug("Subscription cache miss: userId={}", userId);
                return null;
            }
            log.debug("Subscription cache hit: userId={}", userId);
            if (cached instanceof SubscriptionVO vo) {
                return vo;
            }
            return objectMapper.convertValue(cached, SubscriptionVO.class);
        } catch (Exception e) {
            log.warn("Get subscription from cache failed: userId={}, err={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 回填订阅缓存.
     *
     * @param userId 用户 ID
     * @param vo     订阅信息
     */
    public void putToCache(Long userId, SubscriptionVO vo) {
        String key = PaymentRedisKey.subscription(userId);
        try {
            redisTemplate.opsForValue().set(key, vo, SUBSCRIPTION_CACHE_TTL_HOURS, TimeUnit.HOURS);
            log.debug("Subscription cached: userId={}, tier={}", userId, vo.getTier());
        } catch (Exception e) {
            log.warn("Put subscription to cache failed: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 删除订阅缓存(写失效).
     *
     * @param userId 用户 ID
     */
    public void evict(Long userId) {
        String key = PaymentRedisKey.subscription(userId);
        try {
            redisTemplate.delete(key);
            log.debug("Subscription cache evicted: userId={}", userId);
        } catch (Exception e) {
            log.warn("Evict subscription cache failed: userId={}, err={}", userId, e.getMessage());
        }
    }
}
