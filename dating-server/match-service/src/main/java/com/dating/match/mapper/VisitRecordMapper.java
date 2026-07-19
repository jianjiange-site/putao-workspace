package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.VisitRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * visit_record Mapper.
 */
@Mapper
public interface VisitRecordMapper extends BaseMapper<VisitRecordEntity> {

    /**
     * 根据 (from, to) 查询单条.
     */
    @Select("""
            SELECT * FROM visit_record
             WHERE from_user_id = #{from} AND to_user_id = #{to} AND NOT deleted
             LIMIT 1
            """)
    VisitRecordEntity findByPair(@Param("from") long from, @Param("to") long to);

    /**
     * 分页查询"谁访问了我"(按 visited_at DESC).
     */
    @Select("""
            SELECT * FROM visit_record
             WHERE to_user_id = #{userId} AND NOT deleted
             ORDER BY visited_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<VisitRecordEntity> listToUser(@Param("userId") long userId,
                                       @Param("limit") int limit);

    /**
     * 查询某 BH 用户最近 24h 收到的 DH visit 数(用于 24h 上限检查).
     */
    @Select("""
            SELECT COUNT(*) FROM visit_record
             WHERE to_user_id = #{userId} AND from_user_type = #{fromUserType}
               AND visited_at >= #{since} AND NOT deleted
            """)
    long countFromDhSince(@Param("userId") long userId,
                          @Param("fromUserType") int fromUserType,
                          @Param("since") Instant since);
}
