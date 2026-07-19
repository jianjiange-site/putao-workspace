package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.mapper.UserSwipeHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 划卡历史 Manager.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSwipeHistoryManager {

    private final UserSwipeHistoryMapper swipeHistoryMapper;

    /**
     * 查询 (user, target) 的划卡记录.
     *
     * @param userId       用户 ID
     * @param targetUserId 目标 ID
     * @return 命中记录(可能为 null)
     */
    public UserSwipeHistoryEntity findByPair(Long userId, Long targetUserId) {
        return swipeHistoryMapper.findByPair(userId, targetUserId);
    }

    /**
     * 写入划卡历史.
     *
     * <p>同事务由调用方负责.
     */
    public void insert(UserSwipeHistoryEntity entity) {
        swipeHistoryMapper.insert(entity);
    }

    /**
     * 拉取用户所有 swipe 过的 target_id 列表(用于召回排除).
     */
    public List<Long> listSwipedTargetIds(Long userId) {
        return swipeHistoryMapper.listSwipedTargetIds(userId);
    }

    /**
     * 拉取用户最近 N 天的 swipe 记录(用于 D1 偏好建模).
     */
    public List<UserSwipeHistoryEntity> listRecentSwipes(Long userId, Instant since) {
        return swipeHistoryMapper.listRecentSwipes(userId, since);
    }

    /**
     * 用户在某时间窗内是否有过 swipe 行为(D1 cron 前置条件).
     */
    public boolean existsSwipeInRange(Long userId, Instant since, Instant until) {
        return swipeHistoryMapper.existsSwipeInRange(userId, since, until) != null;
    }

    /**
     * 拉取用户在指定时间窗内 swipe 的 target_id(任何方向).
     */
    public List<Long> listSwipedTargetIdsInRange(Long userId, Instant since) {
        LambdaQueryWrapper<UserSwipeHistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(UserSwipeHistoryEntity::getTargetUserId)
                .eq(UserSwipeHistoryEntity::getUserId, userId)
                .ge(UserSwipeHistoryEntity::getSwipedAt, since);
        return swipeHistoryMapper.selectList(wrapper).stream()
                .map(UserSwipeHistoryEntity::getTargetUserId)
                .distinct()
                .toList();
    }
}
