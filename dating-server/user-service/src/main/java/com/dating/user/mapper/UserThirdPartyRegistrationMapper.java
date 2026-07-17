package com.dating.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.entity.UserThirdPartyRegistrationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * UserThirdPartyRegistration Mapper — 第三方绑定表.
 */
@Mapper
public interface UserThirdPartyRegistrationMapper extends BaseMapper<UserThirdPartyRegistrationEntity> {

    /**
     * 按 (platform, thirdPartyUserId) 查 active(deleted=0) 记录.
     */
    @Select("SELECT * FROM user_third_party_registration " +
            "WHERE platform = #{platform} AND third_party_user_id = #{thirdPartyUserId} " +
            "AND app_name = #{appName} AND deleted = 0")
    UserThirdPartyRegistrationEntity findActive(@Param("platform") Integer platform,
                                                @Param("thirdPartyUserId") String thirdPartyUserId,
                                                @Param("appName") String appName);
}
