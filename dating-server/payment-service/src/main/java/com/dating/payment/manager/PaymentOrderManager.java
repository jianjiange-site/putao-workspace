package com.dating.payment.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.payment.entity.PaymentOrderEntity;
import com.dating.payment.mapper.PaymentOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 支付订单 Manager.
 *
 * <p>封装支付订单的数据访问操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOrderManager {

    private final PaymentOrderMapper paymentOrderMapper;

    /**
     * 根据业务订单号查询.
     *
     * @param orderId 业务订单号
     * @return 订单实体
     */
    public Optional<PaymentOrderEntity> findByOrderId(String orderId) {
        LambdaQueryWrapper<PaymentOrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PaymentOrderEntity::getOrderId, orderId);
        return Optional.ofNullable(paymentOrderMapper.selectOne(wrapper));
    }

    public Optional<PaymentOrderEntity> findByOrderIdForUpdate(String orderId) {
        return paymentOrderMapper.findByOrderIdForUpdate(orderId);
    }

    public Optional<PaymentOrderEntity> findByExternalTransaction(
            String channel, String extTransactionId) {
        if (extTransactionId == null || extTransactionId.isBlank()) {
            return Optional.empty();
        }
        return paymentOrderMapper.findByExternalTransaction(channel, extTransactionId);
    }

    /**
     * 根据业务订单号查询（不存在抛异常）.
     *
     * @param orderId 业务订单号
     * @return 订单实体
     */
    public PaymentOrderEntity getByOrderId(String orderId) {
        return findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    /**
     * 保存订单.
     *
     * @param entity 订单实体
     */
    public void save(PaymentOrderEntity entity) {
        paymentOrderMapper.insert(entity);
    }

    /**
     * 更新订单.
     *
     * @param entity 订单实体
     */
    public void updateById(PaymentOrderEntity entity) {
        paymentOrderMapper.updateById(entity);
    }

    /**
     * 更新订单状态.
     *
     * @param orderId 业务订单号
     * @param status  新状态
     * @return 影响行数
     */
    public int updateStatus(String orderId, String status, String... expectedStatuses) {
        LambdaUpdateWrapper<PaymentOrderEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(PaymentOrderEntity::getOrderId, orderId);
        if (expectedStatuses != null && expectedStatuses.length > 0) {
            wrapper.in(PaymentOrderEntity::getStatus, (Object[]) expectedStatuses);
        }
        wrapper.set(PaymentOrderEntity::getStatus, status);
        return paymentOrderMapper.update(null, wrapper);
    }

    /**
     * 更新第三方交易号.
     *
     * @param orderId          业务订单号
     * @param extTransactionId 第三方交易号
     */
    public void updateExtTransactionId(String orderId, String extTransactionId) {
        LambdaQueryWrapper<PaymentOrderEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PaymentOrderEntity::getOrderId, orderId);
        PaymentOrderEntity entity = new PaymentOrderEntity();
        entity.setExtTransactionId(extTransactionId);
        paymentOrderMapper.update(entity, wrapper);
    }
}
