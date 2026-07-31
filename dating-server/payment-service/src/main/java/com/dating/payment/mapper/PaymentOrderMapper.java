package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.PaymentOrderEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * 支付订单 Mapper.
 *
 * <p>对应 payment_orders 表.
 */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrderEntity> {


    @Select("SELECT * FROM payment_orders WHERE order_id = #{orderId} FOR UPDATE")
    Optional<PaymentOrderEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    @Select("""
            SELECT * FROM payment_orders
            WHERE payment_channel = #{channel}
              AND ext_transaction_id = #{extTransactionId}
            LIMIT 1
            """)
    Optional<PaymentOrderEntity> findByExternalTransaction(
            @Param("channel") String channel,
            @Param("extTransactionId") String extTransactionId);
}
