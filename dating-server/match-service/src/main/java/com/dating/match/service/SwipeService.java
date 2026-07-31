package com.dating.match.service;

import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.MatchRedisKey;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.exception.MatchBizException;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.vo.SwipeRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** LEFT/RIGHT swipe orchestration; the database work lives in a proxied transaction service. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwipeService {
    private final UserSwipeHistoryManager swipeHistoryManager;
    private final MatchManager matchManager;
    private final QuotaService quotaService;
    private final MatchTransactionService transactionService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final com.dating.match.client.PaymentServiceClient paymentServiceClient;
    private final FeedService feedService;
    private final RedissonClient redissonClient;

    public SwipeRespVO swipe(long userId, long targetUserId, int direction) {
        if (userId == targetUserId) {
            throw new MatchBizException(MatchErrorCode.SELF_OPERATION, "self swipe");
        }
        if (direction != SwipeDirectionConst.LEFT && direction != SwipeDirectionConst.RIGHT) {
            throw new MatchBizException(MatchErrorCode.MISSING_PARAMETER,
                    "direction must be LEFT or RIGHT");
        }

        RLock lock = redissonClient.getLock(MatchRedisKey.lockSwipe(userId, targetUserId));
        boolean acquired;
        try {
            // No fixed lease: Redisson watchdog renews the lock while RPC/DB work is running.
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "interrupted");
        }
        if (!acquired) {
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "lock not acquired");
        }
        try {
            return doSwipe(userId, targetUserId, direction);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private SwipeRespVO doSwipe(long userId, long targetUserId, int direction) {
        UserSwipeHistoryEntity existing = swipeHistoryManager.findByPair(userId, targetUserId);
        if (existing != null) {
            long[] pair = MatchManager.pair(userId, targetUserId);
            return matchManager.findByPair(pair[0], pair[1])
                    .map(match -> new SwipeRespVO(match.getId(), true))
                    .orElseGet(() -> new SwipeRespVO(0L, true));
        }

        int targetType = userServiceClient.getUserType(targetUserId);
        if (targetType != UserTypeConst.BH && targetType != UserTypeConst.DH) {
            throw new MatchBizException(MatchErrorCode.TARGET_USER_NOT_FOUND,
                    "unsupported target type");
        }

        int tier = paymentServiceClient.getSubscriptionTier(userId);
        if (direction == SwipeDirectionConst.LEFT) {
            quotaService.consumeCardOnly(userId, tier);
        } else {
            quotaService.consumeRightSwipe(userId, tier);
        }

        SwipeRespVO response;
        try {
            //事务处理，记录swipe历史和更新quota
            response = transactionService.recordSwipe(
                    userId, targetUserId, targetType, direction);
        } catch (RuntimeException e) {
            quotaService.rollbackSwipe(userId, direction);
            throw e;
        }


        //更新feed缓存
        feedService.markSwiped(userId, targetUserId);

        return response;
    }
}
