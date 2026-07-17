package com.dating.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.im.entity.UserOnlineSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户在线会话 Mapper.
 *
 * <p>操作 user_online_session 表.
 */
@Mapper
public interface UserOnlineSessionMapper extends BaseMapper<UserOnlineSessionEntity> {
}
