package com.dating.match.service;

import com.dating.match.constant.LikeVisitSourceConst;
import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.MatchEntity;
import com.dating.match.entity.SuperHiOperationEntity;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.SuperHiOperationManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.vo.SuperHiRespVO;
import com.dating.match.vo.SwipeRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Effective Spring proxy boundary for local database transactions. */
@Service
@RequiredArgsConstructor
public class MatchTransactionService {
    private final UserSwipeHistoryManager swipeHistoryManager;
    private final LikeRecordManager likeRecordManager;
    private final MatchManager matchManager;
    private final MatchService matchService;
    private final DhDelayedMatchService delayedMatchService;
    private final SuperHiOperationManager superHiOperationManager;

    @Transactional(rollbackFor = Exception.class)
    public SwipeRespVO recordSwipe(long userId, long targetUserId,
                                   int targetType, int direction) {
        //事务处理，记录swipe历史
        UserSwipeHistoryEntity entity = new UserSwipeHistoryEntity();
        entity.setUserId(userId);
        entity.setTargetUserId(targetUserId);
        entity.setTargetUserType(targetType);
        entity.setDirection(direction);
        entity.setSwipedAt(Instant.now());
        //记录swipe历史
        swipeHistoryManager.insert(entity);

        SwipeRespVO response = new SwipeRespVO();
        response.setIdempotent(false);
        //如果swipe方向为left，直接返回
        if (direction == SwipeDirectionConst.LEFT) {
            return response;
        }

        //如果targetType为DH，则调度延迟匹配
        if (targetType == UserTypeConst.DH) {
            delayedMatchService.scheduleDelayedMatch(userId, targetUserId);
            return response;
        }

        //检查是否为互swipe
        UserSwipeHistoryEntity reverse = swipeHistoryManager.findByPair(targetUserId, userId);
        boolean mutual = reverse != null
                && (reverse.getDirection() == SwipeDirectionConst.RIGHT
                || reverse.getDirection() == SwipeDirectionConst.SUPER_HI);
        if (!mutual) {
            likeRecordManager.upsert(userId, targetUserId, UserTypeConst.BH,
                    LikeVisitSourceConst.SWIPE_RIGHT, null);
            return response;
        }

        //创建匹配
        MatchEntity match = matchService.createMatch(userId, targetUserId, MatchSourceConst.SWIPE_MATCH);
        //设置匹配ID
        response.setMatchId(match == null ? 0L : match.getId());
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public SuperHiRespVO completeSuperHi(SuperHiOperationEntity operation) {
        UserSwipeHistoryEntity existing = swipeHistoryManager.findByPair(
                operation.getUserId(), operation.getTargetUserId());
        MatchEntity match;
        if (existing == null) {
            UserSwipeHistoryEntity entity = new UserSwipeHistoryEntity();
            entity.setUserId(operation.getUserId());
            entity.setTargetUserId(operation.getTargetUserId());
            entity.setTargetUserType(operation.getTargetUserType());
            entity.setDirection(SwipeDirectionConst.SUPER_HI);
            entity.setSwipedAt(Instant.now());
            swipeHistoryManager.insert(entity);
            match = matchService.createMatch(operation.getUserId(), operation.getTargetUserId(),
                    MatchSourceConst.SWIPE_SUPER_HI);
        } else {
            long[] pair = MatchManager.pair(operation.getUserId(), operation.getTargetUserId());
            match = matchManager.findByPair(pair[0], pair[1]).orElse(null);
            if (match == null) {
                match = matchService.createMatch(operation.getUserId(), operation.getTargetUserId(),
                        MatchSourceConst.SWIPE_SUPER_HI);
            }
        }

        long matchId = match == null ? 0L : match.getId();
        superHiOperationManager.markCompleted(operation.getId(), matchId);
        return new SuperHiRespVO(matchId, operation.getCoinsUsed(), false);
    }
}
