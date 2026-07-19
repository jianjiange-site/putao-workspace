package com.dating.payment.manager;

import com.dating.payment.entity.UserSubscriptionEntity;
import com.dating.payment.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用户订阅 Manager.
 *
 * <p>封装用户订阅的数据访问操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSubscriptionManager {

    private final UserSubscriptionMapper userSubscriptionMapper;

    /**
     * 查询用户生效中的订阅.
     *
     * @param userId 用户 ID
     * @return 订阅实体
     */
    public Optional<UserSubscriptionEntity> findActiveByUserId(Long userId) {
        return userSubscriptionMapper.findActiveByUserId(userId);
    }

    /**
     * 保存新订阅.
     *
     * @param entity 订阅实体
     */
    public void save(UserSubscriptionEntity entity) {
        userSubscriptionMapper.insert(entity);
    }

    /**
     * 更新订阅.
     *
     * @param entity 订阅实体
     */
    public void updateById(UserSubscriptionEntity entity) {
        userSubscriptionMapper.updateById(entity);
    }

    /**
     * 软删除订阅.
     *
     * @param userId 用户 ID
     */
    public void softDelete(Long userId) {
        userSubscriptionMapper.findActiveByUserId(userId).ifPresent(entity -> {
            entity.setDeleted(true);
            userSubscriptionMapper.updateById(entity);
        });
    }
}
