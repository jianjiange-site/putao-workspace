package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.UserSwipeHistoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 用于 D1 cron 拉取"昨天有划卡行为"的用户 ID 列表.
 */
@Mapper
public interface D1UserMapper extends BaseMapper<UserSwipeHistoryEntity> {

    /**
     * 拉取在 [since, until) 区间内至少做过一次 swipe 的去重 user_id 列表.
     */
    @Select("""
            SELECT DISTINCT user_id FROM user_swipe_history
             WHERE swiped_at >= #{since} AND swiped_at < #{until} AND NOT deleted
             ORDER BY user_id ASC
             LIMIT #{limit}
            """)
    List<Long> listUsersWithSwipeInRange(@Param("since") Instant since,
                                          @Param("until") Instant until,
                                          @Param("limit") int limit);
}