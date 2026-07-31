package com.dating.match.service;

import com.dating.match.client.PaymentServiceClient;
import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.MatchRedisKey;
import com.dating.match.constant.SubscriptionTierConst;
import com.dating.match.constant.SuperHiOperationStatusConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.SuperHiOperationEntity;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.exception.MatchBizException;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.SuperHiOperationManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.vo.SuperHiRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Recoverable Super Hi saga:
 * Redis quota reservation -> idempotent payment -> local match transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperHiService {
    private final UserSwipeHistoryManager swipeHistoryManager;
    private final MatchManager matchManager;
    private final SuperHiOperationManager operationManager;
    private final QuotaService quotaService;
    private final MatchTransactionService transactionService;
    private final FeedService feedService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final RedissonClient redissonClient;

    public SuperHiRespVO superHi(long userId, long targetUserId) {
        if (userId == targetUserId) {
            throw new MatchBizException(MatchErrorCode.SELF_OPERATION, "self super-hi");
        }
        RLock lock = redissonClient.getLock(MatchRedisKey.lockSwipe(userId, targetUserId));
        boolean acquired;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "interrupted");
        }
        if (!acquired) {
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "lock not acquired");
        }
        try {
            return doSuperHi(userId, targetUserId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private SuperHiRespVO doSuperHi(long userId, long targetUserId) {
        UserSwipeHistoryEntity swipe = swipeHistoryManager.findByPair(userId, targetUserId);
        if (swipe != null) {
            long[] pair = MatchManager.pair(userId, targetUserId);
            return matchManager.findByPair(pair[0], pair[1])
                    .map(match -> new SuperHiRespVO(match.getId(), 0, true))
                    .orElseGet(() -> new SuperHiRespVO(0L, 0, true));
        }

        String operationKey = operationKey(userId, targetUserId);
        SuperHiOperationEntity operation = operationManager.findByOperationKey(operationKey);
        if (operation == null) {
            operation = reserveQuotaAndCreateOperation(userId, targetUserId, operationKey);
        }
        return resume(operation);
    }

    private SuperHiOperationEntity reserveQuotaAndCreateOperation(
            long userId, long targetUserId, String operationKey) {
        int targetType = userServiceClient.getUserType(targetUserId);
        if (targetType != UserTypeConst.BH && targetType != UserTypeConst.DH) {
            throw new MatchBizException(MatchErrorCode.TARGET_USER_NOT_FOUND,
                    "unsupported target type");
        }
        int tier = paymentServiceClient.getSubscriptionTier(userId);
        QuotaService.SuperHiCharge charge = quotaService.consumeSuperHi(
                userId, tier, SubscriptionTierConst.dailySuperHiLimit(tier),
                SubscriptionTierConst.SUPER_HI_COIN_PRICE, operationKey);

        SuperHiOperationEntity operation = new SuperHiOperationEntity();
        operation.setOperationKey(operationKey);
        operation.setUserId(userId);
        operation.setTargetUserId(targetUserId);
        operation.setTargetUserType(targetType);
        operation.setSubscriptionTier(tier);
        operation.setCoinsUsed(charge.coinsUsed());
        operation.setStatus(SuperHiOperationStatusConst.QUOTA_RESERVED);
        return operationManager.insertOrGet(operation);
    }

    private SuperHiRespVO resume(SuperHiOperationEntity operation) {
        if (SuperHiOperationStatusConst.COMPLETED.equals(operation.getStatus())) {
            return new SuperHiRespVO(operation.getMatchId(), operation.getCoinsUsed(), true);
        }

        if (operation.getCoinsUsed() != null && operation.getCoinsUsed() > 0
                && SuperHiOperationStatusConst.QUOTA_RESERVED.equals(operation.getStatus())) {
            PaymentServiceClient.ConsumeResult payment;
            try {
                payment = paymentServiceClient.consumeCoins(
                        operation.getUserId(), operation.getCoinsUsed(),
                        operation.getOperationKey(), "SUPER_HI");
            } catch (RuntimeException e) {
                operationManager.updateProgress(operation.getId(), operation.getStatus(),
                        operation.getCoinsUsed(), e.getMessage());
                throw e;
            }
            if (!payment.ok()) {
                quotaService.rollbackSuperHi(operation.getUserId(), operation.getOperationKey());
                operationManager.hardDelete(operation.getId());
                throw new MatchBizException(MatchErrorCode.SUPER_HI_INSUFFICIENT,
                        "insufficient coins");
            }
            operation.setStatus(SuperHiOperationStatusConst.COINS_CHARGED);
            operationManager.updateProgress(operation.getId(), operation.getStatus(),
                    operation.getCoinsUsed(), null);
        }

        try {
            SuperHiRespVO response = transactionService.completeSuperHi(operation);
            try {
                feedService.markSwiped(operation.getUserId(), operation.getTargetUserId());
            } catch (Exception cacheError) {
                log.warn("Super Hi markSwiped failed operationKey={} err={}",
                        operation.getOperationKey(), cacheError.getMessage());
            }
            return response;
        } catch (RuntimeException e) {
            operationManager.updateProgress(operation.getId(), operation.getStatus(),
                    operation.getCoinsUsed(), e.getMessage());
            throw e;
        }
    }

    public int recoverPendingOperations() {
        List<SuperHiOperationEntity> operations =
                operationManager.listRecoverable(Instant.now().minusSeconds(10), 100);
        int recovered = 0;
        for (SuperHiOperationEntity operation : operations) {
            RLock lock = redissonClient.getLock(
                    MatchRedisKey.lockSwipe(operation.getUserId(), operation.getTargetUserId()));
            boolean acquired = false;
            try {
                acquired = lock.tryLock();
                if (acquired) {
                    resume(operation);
                    recovered++;
                }
            } catch (Exception e) {
                log.warn("Super Hi recovery failed operationKey={} err={}",
                        operation.getOperationKey(), e.getMessage());
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        return recovered;
    }

    private String operationKey(long userId, long targetUserId) {
        return "super_hi:" + userId + ":" + targetUserId;
    }
}
