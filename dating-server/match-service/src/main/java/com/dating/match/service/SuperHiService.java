package com.dating.match.service;

import com.dating.match.client.PaymentServiceClient;
import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.SubscriptionTierConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.LikeRecordEntity;
import com.dating.match.entity.MatchEntity;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.exception.MatchBizException;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.vo.SuperHiRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Super Hi 服务(独立 RPC).
 *
 * <p>语义区别于普通 swipe:订阅赠送 + 金币扣减;无论对方是否喜欢过我都立即触发 match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperHiService {

    private final UserSwipeHistoryManager swipeHistoryManager;
    private final MatchManager matchManager;
    private final LikeRecordManager likeRecordManager;
    private final QuotaService quotaService;
    private final MatchService matchService;
    private final FeedService feedService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final com.dating.match.client.PaymentServiceClient paymentServiceClient;
    private final RedissonClient redissonClient;

    /**
     * 执行一次 Super Hi.
     */
    public SuperHiRespVO superHi(long userId, long targetUserId) {
        // 如果 userId 和 targetUserId 相同，抛出异常
        if (userId == targetUserId) {
            throw new MatchBizException(MatchErrorCode.SELF_OPERATION, "self super-hi");
        }

        // 获取锁 key
        String lockKey = com.dating.match.constant.MatchRedisKey.lockSwipe(userId, targetUserId);
        // 获取锁
        RLock lock = redissonClient.getLock(lockKey);
        // 尝试获取锁
        boolean acquired;
        // 尝试获取锁，5秒内获取不到则抛出异常，3秒后释放锁
        try {
            acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "interrupted");
        }
        if (!acquired) {
            // 锁获取失败，抛出异常
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "lock not acquired");
        }
        try {
            // 执行 SuperHi
            return doSuperHi(userId, targetUserId);
        } finally {
            // 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private SuperHiRespVO doSuperHi(long userId, long targetUserId) {
        // 1. 幂等检查
        // 查询用户 swipe history
        UserSwipeHistoryEntity existing = swipeHistoryManager.findByPair(userId, targetUserId);
        // 如果 existing 不为空，且 direction 不为空，且 direction 为 SUPER_HI，则返回上次结果
        if (existing != null && existing.getDirection() != null
                && existing.getDirection() == com.dating.match.constant.SwipeDirectionConst.SUPER_HI) {
            // 已 SuperHi 过 — 返回上次结果
            long[] pair = MatchManager.pair(userId, targetUserId);
            return matchManager.findByPair(pair[0], pair[1])
                    .map(m -> new SuperHiRespVO(m.getId(), 0, true))
                    .orElseGet(() -> new SuperHiRespVO(0L, 0, true));
        }

        // 2. 查 target 类型
        int targetType = userServiceClient.getUserType(targetUserId);
        if (targetType != UserTypeConst.BH && targetType != UserTypeConst.DH) {
            throw new MatchBizException(MatchErrorCode.TARGET_USER_NOT_FOUND, "unsupported target type");
        }

        // 3. 配额扣减(订阅赠送优先,否则金币)
        // 获取用户订阅等级
        int tier = paymentServiceClient.getSubscriptionTier(userId);
        // 获取每日 SuperHi 限制
        int giftLimit = SubscriptionTierConst.dailySuperHiLimit(tier);
        // 扣减 SuperHi 配额，返回 SuperHiCharge 实体
        QuotaService.SuperHiCharge charge = quotaService.consumeSuperHi(userId, tier, giftLimit,
                SubscriptionTierConst.SUPER_HI_COIN_PRICE);

        // 扣减金币
        int coinsUsed = 0;
        if (charge.needCoinCharge()) {
            // 扣金币
            // 获取幂等 key，用于防止重复扣减金币
            String idemKey = "super_hi:" + userId + ":" + targetUserId;
            // 扣减金币，返回 ConsumeResult 实体
            try {
                // 扣减金币，返回 ConsumeResult 实体
                PaymentServiceClient.ConsumeResult res = paymentServiceClient.consumeCoins(
                        userId, charge.coinsUsed(), idemKey, "SUPER_HI");
                if (!res.ok()) {
                    // 回滚 SuperHi 配额（回滚card、right计数）
                    quotaService.rollbackSuperHi(userId);
                    throw new MatchBizException(MatchErrorCode.SUPER_HI_INSUFFICIENT, "insufficient coins");
                }
                // 设置扣减的金币数量
                coinsUsed = charge.coinsUsed();
            } catch (MatchBizException e) {
                throw e;
            } catch (Exception e) {
                // 回滚 SuperHi 配额
                quotaService.rollbackSuperHi(userId);
                throw new MatchBizException(MatchErrorCode.SUPER_HI_INSUFFICIENT, "consume coins failed");
            }
        }

        // 4. 写历史 + 触发 match
        // 写历史 + 触发 match，返回 SuperHiRespVO 实体
        SuperHiRespVO resp = writeHistoryAndTrigger(userId, targetUserId, targetType);
        // 设置扣减的金币数量
        resp.setCoinsUsed(coinsUsed);

        // 5. SADD 到 match:swiped
        feedService.markSwiped(userId, targetUserId);
        return resp;
    }

    /**
     * 写 SUPER_HI 历史 + 立即 match.
     */
    @Transactional(rollbackFor = Exception.class)
    public SuperHiRespVO writeHistoryAndTrigger(long userId, long targetUserId, int targetType) {
        // 创建 UserSwipeHistoryEntity 实体
        UserSwipeHistoryEntity entity = new UserSwipeHistoryEntity();
        entity.setUserId(userId);
        entity.setTargetUserId(targetUserId);
        entity.setTargetUserType(targetType);
        entity.setDirection(com.dating.match.constant.SwipeDirectionConst.SUPER_HI);
        entity.setSwipedAt(Instant.now());
        swipeHistoryManager.insert(entity);

        // 创建 MatchEntity 实体
        MatchEntity match = matchService.createMatch(userId, targetUserId, MatchSourceConst.SWIPE_SUPER_HI);
        // 创建 SuperHiRespVO 实体
        SuperHiRespVO resp = new SuperHiRespVO();
        // 设置 match id
        resp.setMatchId(match != null ? match.getId() : 0L);
        // 设置幂等
        resp.setIdempotent(false);
        // 返回 SuperHiRespVO 实体
        return resp;
    }
}