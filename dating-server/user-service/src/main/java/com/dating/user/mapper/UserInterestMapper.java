package com.dating.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.entity.UserInterestEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * UserInterest Mapper — 兴趣标签表.
 *
 * <p>{@code ReplaceUserInterests} 用事务内 deleteByUserId + batchInsert 替换.
 */
@Mapper
public interface UserInterestMapper extends BaseMapper<UserInterestEntity> {

    /** 按 user_id 列出全部兴趣(按 sort_order ASC) */
    @Select("SELECT * FROM user_interest WHERE user_id = #{userId} ORDER BY sort_order ASC, id ASC")
    List<UserInterestEntity> listByUserId(@Param("userId") Long userId);

    /** 按 user_id 批量查(用于 BatchGetProfile) */
    @Select("SELECT * FROM user_interest WHERE user_id IN " +
            "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach> ORDER BY user_id ASC, sort_order ASC")
    List<UserInterestEntity> listByUserIds(@Param("userIds") Collection<Long> userIds);

    /** 全量替换 — 先按 user_id 清空 */
    @Delete("DELETE FROM user_interest WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
