package com.dating.match.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.match.entity.MatchEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * match 主表 Mapper.
 */
@Mapper
public interface MatchMapper extends BaseMapper<MatchEntity> {

    /**
     * 根据 (low, high) 查询.
     */
    @Select("""
            SELECT * FROM match
             WHERE user_id_low = #{low} AND user_id_high = #{high} AND NOT deleted
             LIMIT 1
            """)
    MatchEntity findByPair(@Param("low") long low, @Param("high") long high);

    /**
     * 分页查询用户 match(low + high 两侧 UNION).
     */
    @Select("""
            SELECT * FROM match
             WHERE (user_id_low = #{userId} OR user_id_high = #{userId}) AND NOT deleted
             ORDER BY matched_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<MatchEntity> listByUser(@Param("userId") long userId,
                                 @Param("limit") int limit);
}
