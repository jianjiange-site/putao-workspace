package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.SuperHiOperationEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface SuperHiOperationMapper extends BaseMapper<SuperHiOperationEntity> {
    @Select("""
            SELECT * FROM super_hi_operation
             WHERE operation_key = #{operationKey} AND NOT deleted
             LIMIT 1
            """)
    SuperHiOperationEntity findByOperationKey(@Param("operationKey") String operationKey);

    @Select("""
            SELECT * FROM super_hi_operation
             WHERE status IN ('QUOTA_RESERVED', 'COINS_CHARGED')
               AND updated_at <= #{before} AND NOT deleted
             ORDER BY updated_at
             LIMIT #{limit}
            """)
    List<SuperHiOperationEntity> listRecoverable(@Param("before") Instant before,
                                                 @Param("limit") int limit);

    @Update("""
            UPDATE super_hi_operation
               SET status = #{status}, coins_used = #{coinsUsed},
                   last_error = #{lastError}, updated_at = now()
             WHERE id = #{id} AND NOT deleted
            """)
    int updateProgress(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("coinsUsed") int coinsUsed,
                       @Param("lastError") String lastError);

    @Update("""
            UPDATE super_hi_operation
               SET status = 'COMPLETED', match_id = #{matchId},
                   last_error = NULL, updated_at = now()
             WHERE id = #{id} AND NOT deleted
            """)
    int markCompleted(@Param("id") Long id, @Param("matchId") Long matchId);

    @Delete("DELETE FROM super_hi_operation WHERE id = #{id}")
    int hardDelete(@Param("id") Long id);
}
