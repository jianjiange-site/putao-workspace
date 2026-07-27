package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.LikeRecordEntity;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.exception.MatchBizException;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.vo.SwipeRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 普通划卡(LEFT / RIGHT)服务.
 *
 * <p>幂等:同 (user, target) 二次 swipe 返回上次结果,不扣配额.
 * 并发:Redisson 锁 {@code lock:match:swipe:&lt;user&gt;:&lt;target&gt;} 串行化.
 *
 * <p>触发 match 的场景:
 * <ul>
 *   <li>RIGHT + target BH + target 已 RIGHT/SUPER_HI 过 user → 即时 match</li>
 *   <li>RIGHT + target DH → 15s-2min 延迟 match(委托 {@link DhDelayedMatchService})</li>
 *   <li>LEFT → 仅写历史,无 match</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwipeService {

    private final UserSwipeHistoryManager swipeHistoryManager;
    private final LikeRecordManager likeRecordManager;
    private final MatchManager matchManager;
    private final QuotaService quotaService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final com.dating.match.client.PaymentServiceClient paymentServiceClient;
    private final DhDelayedMatchService dhDelayedMatchService;
    private final FeedService feedService;
    private final RedissonClient redissonClient;
    private final MatchProperties props;

    /**
     * 执行一次划卡.
     *
     * @param userId       划卡方
     * @param targetUserId 目标用户
     * @param direction    LEFT / RIGHT
     * @return SwipeRespVO:matchId > 0 即匹配成功
     */
    public SwipeRespVO swipe(long userId, long targetUserId, int direction) {
        if (userId == targetUserId) {
            throw new MatchBizException(MatchErrorCode.SELF_OPERATION, "self swipe");
        }
        if (direction != SwipeDirectionConst.LEFT && direction != SwipeDirectionConst.RIGHT) {
            throw new MatchBizException(MatchErrorCode.MISSING_PARAMETER, "direction must be LEFT or RIGHT");
        }

        // 1. 串行化锁，防止并发划卡（幂等性）
        String lockKey = com.dating.match.constant.MatchRedisKey.lockSwipe(userId, targetUserId);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            // 尝试获取锁，5秒内获取不到则抛出异常，3秒后释放锁
            acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 中断线程
            Thread.currentThread().interrupt();
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "interrupted");
        }
        if (!acquired) {
            // 锁获取失败，抛出异常
            throw new MatchBizException(MatchErrorCode.CONCURRENT_SWIPE, "lock not acquired");
        }
        try {
            // 执行划卡
            return doSwipe(userId, targetUserId, direction);
        } finally {
            // 释放锁
            // 判断是否当前线程持有锁
            if (lock.isHeldByCurrentThread()) {
                // 释放锁
                lock.unlock();
            }
        }
    }

    private SwipeRespVO doSwipe(long userId, long targetUserId, int direction) {
        // 2. 幂等:已存在 swipe 记录 → 返回上次结果
        UserSwipeHistoryEntity existing = swipeHistoryManager.findByPair(userId, targetUserId);
        if (existing != null) {
            log.debug("swipe idempotent userId={} targetId={} dir={}", userId, targetUserId, direction);
            // 二次 swipe:不能简单返回 0,需要查 match
            // 获取 pair
            long[] pair = MatchManager.pair(userId, targetUserId);
            // 查询 match
            // 如果 match 存在，返回 match id 和 false
            // 如果 match 不存在，返回 0 和 false
            return matchManager.findByPair(pair[0], pair[1])
                    .map(m -> new SwipeRespVO(m.getId(), false))
                    .orElseGet(() -> new SwipeRespVO(0L, false));
        }

        // 3. 查 target 类型(决定后续流程)
        int targetType = userServiceClient.getUserType(targetUserId);
        // 如果 target type 小于等于 0，抛出异常
        if (targetType <= 0) {
            throw new MatchBizException(MatchErrorCode.TARGET_USER_NOT_FOUND, "target type unknown");
        }
        // 如果 target type 不是 BH 或 DH，抛出异常
        if (targetType != UserTypeConst.BH && targetType != UserTypeConst.DH) {
            throw new MatchBizException(MatchErrorCode.TARGET_USER_NOT_FOUND, "unsupported target type");
        }

        // 4. 配额扣减
        // 获取用户订阅等级
        int tier = paymentServiceClient.getSubscriptionTier(userId);
        // 如果方向是 LEFT，扣减卡片配额
        if (direction == SwipeDirectionConst.LEFT) {
            quotaService.consumeCardOnly(userId, tier);
        } else {
            // 如果方向是 RIGHT，扣减配额
            quotaService.consumeRightSwipe(userId, tier);
        }

        // 5. 写 user_swipe_history(同事务，防止并发写入)
        SwipeRespVO resp = writeHistoryAndTrigger(userId, targetUserId, targetType, direction);
        // 6. SADD 到 match:swiped(消费阶段二次过滤)
        feedService.markSwiped(userId, targetUserId);
        return resp;
    }

    /**
     * 写历史 + 触发 match / like / 延迟回调.
     */
    @Transactional(rollbackFor = Exception.class)
    public SwipeRespVO writeHistoryAndTrigger(long userId, long targetUserId, int targetType, int direction) {
        // 创建 user_swipe_history 实体
        UserSwipeHistoryEntity entity = new UserSwipeHistoryEntity();
        entity.setUserId(userId);
        entity.setTargetUserId(targetUserId);
        entity.setTargetUserType(targetType);
        entity.setDirection(direction);
        entity.setSwipedAt(Instant.now());
        // 插入 user_swipe_history
        swipeHistoryManager.insert(entity);
        // 创建 SwipeRespVO 实体
        SwipeRespVO resp = new SwipeRespVO();
        resp.setIdempotent(false);
        // 如果方向是 LEFT，返回 resp
        if (direction == SwipeDirectionConst.LEFT) {
            return resp;
        }

        // RIGHT 路径
        // 如果 target type 是 BH，处理 BH 路径
        if (targetType == UserTypeConst.BH) {
            // 互划匹配?查 target 是否曾对 user 做过 RIGHT/SUPER_HI
            UserSwipeHistoryEntity reverse = swipeHistoryManager.findByPair(targetUserId, userId);
            // 如果 reverse 不为空，且 direction 为 RIGHT 或 SUPER_HI，则即时 match
            if (reverse != null
                    && (reverse.getDirection() == SwipeDirectionConst.RIGHT
                    || reverse.getDirection() == SwipeDirectionConst.SUPER_HI)) {
                // 即时 match
                long[] pair = MatchManager.pair(userId, targetUserId);
                // 插入 match
                MatchManager.InsertResult insertResult = matchManager.insertIgnoreConflictWithLog(
                        userId, targetUserId, com.dating.match.constant.MatchSourceConst.SWIPE_MATCH);
                if (insertResult.success()) {
                    // 同事务清理双向 like_record
                    likeRecordManager.softDeleteByPair(userId, targetUserId);
                    // 设置 match id
                    resp.setMatchId(insertResult.match().getId());
                } else {
                    // 设置 match id
                    resp.setMatchId(insertResult.match() != null ? insertResult.match().getId() : 0L);
                }
            } else {
                // 单向 → UPSERT like_record，记录用户对 target 的 RIGHT 划卡
                likeRecordManager.upsert(userId, targetUserId, UserTypeConst.BH,
                        com.dating.match.constant.LikeVisitSourceConst.SWIPE_RIGHT, null);
            }
        } else {
            // target DH → 延迟 match
            // 注意:延迟回调(DhDelayedMatchService)负责后续 createMatch + 副作用
            dhDelayedMatchService.scheduleDelayedMatch(userId, targetUserId);
        }
        return resp;
    }
}