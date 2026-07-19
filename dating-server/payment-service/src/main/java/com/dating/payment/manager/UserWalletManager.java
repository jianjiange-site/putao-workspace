package com.dating.payment.manager;

import com.dating.payment.entity.UserWalletEntity;
import com.dating.payment.mapper.UserWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用户钱包 Manager.
 *
 * <p>封装用户钱包的数据访问操作（提现功能）.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserWalletManager {

    private final UserWalletMapper userWalletMapper;

    /**
     * 根据用户 ID 查询钱包.
     *
     * @param userId 用户 ID
     * @return 钱包实体
     */
    public Optional<UserWalletEntity> findByUserId(Long userId) {
        return Optional.ofNullable(userWalletMapper.selectById(userId));
    }

    /**
     * 保存钱包.
     *
     * @param entity 钱包实体
     */
    public void save(UserWalletEntity entity) {
        userWalletMapper.insert(entity);
    }

    /**
     * 更新钱包.
     *
     * @param entity 钱包实体
     */
    public void updateById(UserWalletEntity entity) {
        userWalletMapper.updateById(entity);
    }
}
