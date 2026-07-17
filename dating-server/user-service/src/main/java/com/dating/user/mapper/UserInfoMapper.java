package com.dating.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.entity.UserInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * UserInfo Mapper — 单表 CRUD,负责 user_info.
 *
 * <p>复杂查询(如按 city/age/race 召回)放 service 层多次单表查后组装,严禁 JOIN.
 */
@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfoEntity> {

    /**
     * 按业务主键查询(单表,走 idx_user_info_user_id).
     *
     * @param userId 业务主键
     * @return 实体或 null
     */
    @Select("SELECT * FROM user_info WHERE user_id = #{userId} AND deleted = 0")
    UserInfoEntity selectByUserId(@Param("userId") Long userId);

    /**
     * 批量按业务主键 IN 一次捞,用于 BatchGetProfile.
     *
     * @param userIds 去重后的 userId 列表
     * @return 实体列表(顺序不保证,调用方按需自行排)
     */
    @Select("SELECT * FROM user_info WHERE user_id IN " +
            "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach> AND deleted = 0")
    List<UserInfoEntity> selectByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 插入 placeholder 用户并返回 user_id(雪花预生成后传入).
     *
     * @param entity 已填充 userId / nickname='User_${userId}' 等
     */
    default void insertPlaceholder(UserInfoEntity entity) {
        insert(entity);
    }

    /**
     * 更新 last_open_at 列,每次 ResolveOrCreate 命中时调用.
     *
     * <p>仅触发表中这一列,避免 updated_at 触发器误把其他字段也标脏.
     */
    @Select("UPDATE user_info SET last_open_at = #{lastOpenAt}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId} AND deleted = 0")
    int touchLastOpenAt(@Param("userId") Long userId,
                        @Param("lastOpenAt") Instant lastOpenAt);
}
