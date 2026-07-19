package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.LikeRecordEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * like_record Mapper.
 */
@Mapper
public interface LikeRecordMapper extends BaseMapper<LikeRecordEntity> {

    /**
     * 根据 (from, to) 查询单条.
     */
    @Select("""
            SELECT * FROM like_record
             WHERE from_user_id = #{from} AND to_user_id = #{to} AND NOT deleted
             LIMIT 1
            """)
    LikeRecordEntity findByPair(@Param("from") long from, @Param("to") long to);

    /**
     * 双方 like_record 全部清理(match 创建副作用).
     */
    @Delete("""
            UPDATE like_record SET deleted = true, updated_at = now()
             WHERE ((from_user_id = #{a} AND to_user_id = #{b})
                 OR (from_user_id = #{b} AND to_user_id = #{a}))
               AND NOT deleted
            """)
    int softDeleteByPair(@Param("a") long a, @Param("b") long b);

    /**
     * 分页查询"谁 like 了我"(按 liked_at DESC).
     */
    @Select("""
            SELECT * FROM like_record
             WHERE to_user_id = #{userId} AND NOT deleted
             ORDER BY liked_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<LikeRecordEntity> listToUser(@Param("userId") long userId,
                                      @Param("limit") int limit);

    /**
     * 查询某 BH 用户最近 24h 收到的 DH like 数(用于 24h 上限检查).
     */
    @Select("""
            SELECT COUNT(*) FROM like_record
             WHERE to_user_id = #{userId} AND from_user_type = #{fromUserType}
               AND liked_at >= #{since} AND NOT deleted
            """)
    long countFromDhSince(@Param("userId") long userId,
                          @Param("fromUserType") int fromUserType,
                          @Param("since") Instant since);
}
