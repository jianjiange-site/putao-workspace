package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.UserSwipeHistoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 划卡历史 Mapper.
 */
@Mapper
public interface UserSwipeHistoryMapper extends BaseMapper<UserSwipeHistoryEntity> {

    /**
     * 查询用户对 target 是否已 swipe 过(幂等检查).
     *
     * @param userId       用户 ID
     * @param targetUserId 目标用户 ID
     * @return 命中记录
     */
    @Select("""
            SELECT * FROM user_swipe_history
             WHERE user_id = #{userId} AND target_user_id = #{targetUserId} AND NOT deleted
             LIMIT 1
            """)
    UserSwipeHistoryEntity findByPair(@Param("userId") Long userId,
                                       @Param("targetUserId") Long targetUserId);

    /**
     * 拉取用户在指定时间窗内 swipe 过的所有 target(用于 D1 偏好建模).
     */
    @Select("""
            SELECT * FROM user_swipe_history
             WHERE user_id = #{userId} AND direction = #{direction}
               AND swiped_at >= #{since} AND NOT deleted
            """)
    List<UserSwipeHistoryEntity> listByUserAndDirection(@Param("userId") Long userId,
                                                        @Param("direction") int direction,
                                                        @Param("since") Instant since);

    /**
     * 拉取用户所有 swipe 过的 target_id(用于召回排除).
     */
    @Select("""
            SELECT DISTINCT target_user_id FROM user_swipe_history
             WHERE user_id = #{userId} AND NOT deleted
            """)
    List<Long> listSwipedTargetIds(@Param("userId") Long userId);

    /**
     * 拉取用户在最近时间窗内(默认 30 天)有 swipe 行为的 target 详情(用于偏好建模).
     */
    @Select("""
            SELECT * FROM user_swipe_history
             WHERE user_id = #{userId}
               AND swiped_at >= #{since} AND NOT deleted
             ORDER BY swiped_at DESC
            """)
    List<UserSwipeHistoryEntity> listRecentSwipes(@Param("userId") Long userId,
                                                   @Param("since") Instant since);

    /**
     * 检查用户昨天是否有划卡行为(D1 cron 前置条件).
     */
    @Select("""
            SELECT 1 FROM user_swipe_history
             WHERE user_id = #{userId}
               AND swiped_at >= #{since} AND swiped_at < #{until} AND NOT deleted
             LIMIT 1
            """)
    Integer existsSwipeInRange(@Param("userId") Long userId,
                               @Param("since") Instant since,
                               @Param("until") Instant until);
}
