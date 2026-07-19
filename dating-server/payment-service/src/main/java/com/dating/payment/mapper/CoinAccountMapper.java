package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.CoinAccountEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 金币账户 Mapper.
 *
 * <p>对应 coin_accounts 表.
 */
@Mapper
public interface CoinAccountMapper extends BaseMapper<CoinAccountEntity> {

}
