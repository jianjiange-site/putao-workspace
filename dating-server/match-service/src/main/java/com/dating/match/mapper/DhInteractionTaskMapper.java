package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.DhInteractionTaskEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * dh_interaction_task Mapper.
 */
@Mapper
public interface DhInteractionTaskMapper extends BaseMapper<DhInteractionTaskEntity> {

    /**
     * 扫描到期任务(executor 用).
     */
    @Select("""
            SELECT * FROM dh_interaction_task
             WHERE execute_time <= #{now}
             ORDER BY execute_time ASC
             LIMIT #{limit}
            """)
    List<DhInteractionTaskEntity> scanDueTasks(@Param("now") Instant now,
                                               @Param("limit") int limit);

    /**
     * 检查某用户在某 scene 下是否已有未执行任务(generator 去重闸).
     */
    @Select("""
            SELECT 1 FROM dh_interaction_task
             WHERE to_user_id = #{userId} AND scene = #{scene}
             LIMIT 1
            """)
    Integer existsByScene(@Param("userId") long userId,
                          @Param("scene") int scene);

    /**
     * 单条硬删.
     */
    @Delete("DELETE FROM dh_interaction_task WHERE id = #{id}")
    int hardDelete(@Param("id") Long id);

    /**
     * 积压监控:execute_time + 5min < now 仍未执行的任务数.
     */
    @Select("""
            SELECT COUNT(*) FROM dh_interaction_task
             WHERE execute_time < #{threshold}
            """)
    long countOverdue(@Param("threshold") Instant threshold);
}
