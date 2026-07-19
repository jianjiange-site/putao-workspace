package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.UserSubscriptionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 用户订阅 Mapper.
 *
 * <p>对应 user_subscription 表.
 */
@Mapper
public interface UserSubscriptionMapper extends BaseMapper<UserSubscriptionEntity> {

    /**
     * 查询用户生效中的订阅（deleted=false）.
     *
     * @param userId 用户 ID
     * @return 订阅记录
     */
    @Select("SELECT * FROM user_subscription WHERE user_id = #{userId} AND deleted = false LIMIT 1")
    Optional<UserSubscriptionEntity> findActiveByUserId(@Param("userId") Long userId);
}
