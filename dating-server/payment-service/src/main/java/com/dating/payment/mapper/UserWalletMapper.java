package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.UserWalletEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户钱包 Mapper.
 *
 * <p>对应 user_wallets 表，用于提现功能.
 */
@Mapper
public interface UserWalletMapper extends BaseMapper<UserWalletEntity> {

}
