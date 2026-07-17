package com.dating.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.entity.UserDeviceRegistrationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * UserDeviceRegistration Mapper — 设备绑定表(快速登录).
 */
@Mapper
public interface UserDeviceRegistrationMapper extends BaseMapper<UserDeviceRegistrationEntity> {

    /**
     * 按 (deviceId, platform, appName) 查 active(deleted=0) 记录.
     */
    @Select("SELECT * FROM user_device_registration " +
            "WHERE device_id = #{deviceId} AND platform = #{platform} " +
            "AND app_name = #{appName} AND deleted = 0")
    UserDeviceRegistrationEntity findActive(@Param("deviceId") String deviceId,
                                            @Param("platform") Integer platform,
                                            @Param("appName") String appName);
}
