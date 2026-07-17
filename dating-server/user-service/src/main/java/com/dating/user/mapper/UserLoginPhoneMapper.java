package com.dating.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.entity.UserLoginPhoneEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * UserLoginPhone Mapper — 手机号绑定表.
 */
@Mapper
public interface UserLoginPhoneMapper extends BaseMapper<UserLoginPhoneEntity> {

    /**
     * 按 (phone_e164, app_name) 唯一索引查;唯一约束在 DB 层强制.
     */
    @Select("SELECT * FROM user_login_phone " +
            "WHERE phone_e164 = #{phoneE164} AND app_name = #{appName} AND deleted = 0")
    UserLoginPhoneEntity findByPhoneAndApp(@Param("phoneE164") String phoneE164,
                                           @Param("appName") String appName);
}
