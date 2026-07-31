package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.DelayedMatchTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DelayedMatchTaskMapper extends BaseMapper<DelayedMatchTaskEntity> {

    @Select("""
            WITH picked AS (
                SELECT id
                  FROM delayed_match_task
                 WHERE NOT deleted
                   AND next_retry_at <= #{now}
                   AND (
                        status = 'PENDING'
                        OR (status = 'PROCESSING' AND locked_until <= #{now})
                   )
                 ORDER BY next_retry_at, id
                 FOR UPDATE SKIP LOCKED
                 LIMIT #{limit}
            )
            UPDATE delayed_match_task t
               SET status = 'PROCESSING',
                   locked_by = #{workerId},
                   locked_until = #{leaseUntil},
                   updated_at = now()
              FROM picked
             WHERE t.id = picked.id
            RETURNING t.*
            """)
    List<DelayedMatchTaskEntity> claimDue(@Param("now") Instant now,
                                          @Param("limit") int limit,
                                          @Param("workerId") String workerId,
                                          @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE delayed_match_task
               SET status = 'DONE', locked_by = NULL, locked_until = NULL, updated_at = now()
             WHERE id = #{id} AND status = 'PROCESSING' AND locked_by = #{workerId}
            """)
    int markDone(@Param("id") Long id, @Param("workerId") String workerId);

    @Update("""
            UPDATE delayed_match_task
               SET attempts = attempts + 1,
                   next_retry_at = #{nextRetryAt},
                   status = CASE WHEN attempts + 1 >= #{maxAttempts} THEN 'DEAD' ELSE 'PENDING' END,
                   locked_by = NULL,
                   locked_until = NULL,
                   last_error = #{lastError},
                   updated_at = now()
             WHERE id = #{id} AND status = 'PROCESSING' AND locked_by = #{workerId}
            """)
    int markRetry(@Param("id") Long id,
                  @Param("workerId") String workerId,
                  @Param("nextRetryAt") Instant nextRetryAt,
                  @Param("maxAttempts") int maxAttempts,
                  @Param("lastError") String lastError);
}
