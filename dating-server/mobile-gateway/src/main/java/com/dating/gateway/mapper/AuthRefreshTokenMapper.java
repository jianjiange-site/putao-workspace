package com.dating.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.gateway.entity.AuthRefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthRefreshTokenMapper extends BaseMapper<AuthRefreshTokenEntity> {
}
