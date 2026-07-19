package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.PaymentOrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付订单 Mapper.
 *
 * <p>对应 payment_orders 表.
 */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrderEntity> {

}
