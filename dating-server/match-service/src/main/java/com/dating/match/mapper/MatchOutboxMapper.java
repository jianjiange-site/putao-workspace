package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.MatchOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * match_outbox Mapper.
 */
@Mapper
public interface MatchOutboxMapper extends BaseMapper<MatchOutboxEntity> {

    /**
     * 拉取到期待处理任务.
     */
    @Select("""
            SELECT * FROM match_outbox
             WHERE status = 'PENDING' AND next_retry_at <= #{now} AND NOT deleted
             ORDER BY next_retry_at ASC
             LIMIT #{limit}
            """)
    List<MatchOutboxEntity> listPending(@Param("now") Instant now,
                                        @Param("limit") int limit);

    /**
     * 标记 DONE.
     */
    @Update("""
            UPDATE match_outbox SET status = 'DONE', updated_at = now()
             WHERE id = #{id} AND NOT deleted
            """)
    int markDone(@Param("id") Long id);

    /**
     * 标记失败 + 增加 attempts + 推进 next_retry_at.
     */
    @Update("""
            UPDATE match_outbox
               SET attempts = attempts + 1,
                   next_retry_at = #{nextRetryAt},
                   status = CASE WHEN attempts + 1 >= #{maxAttempts} THEN 'DEAD' ELSE 'PENDING' END,
                   updated_at = now()
             WHERE id = #{id} AND NOT deleted
            """)
    int markRetry(@Param("id") Long id,
                  @Param("nextRetryAt") Instant nextRetryAt,
                  @Param("maxAttempts") int maxAttempts);
}
