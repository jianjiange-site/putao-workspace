package com.dating.payment.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.payment.entity.CoinAccountEntity;
import com.dating.payment.mapper.CoinAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 金币账户 Manager.
 *
 * <p>封装金币账户的数据访问和乐观锁更新操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinAccountManager {

    private final CoinAccountMapper coinAccountMapper;

    /**
     * 根据用户 ID 查询账户.
     *
     * @param userId 用户 ID
     * @return 账户实体
     */
    public Optional<CoinAccountEntity> findByUserId(Long userId) {
        return Optional.ofNullable(coinAccountMapper.selectById(userId));
    }

    /**
     * 根据用户 ID 查询账户（不存在则创建空账户）.
     *
     * @param userId 用户 ID
     * @return 账户实体
     */
    public CoinAccountEntity getOrCreate(Long userId) {
        return findByUserId(userId).orElseGet(() -> {
            CoinAccountEntity entity = new CoinAccountEntity();
            entity.setUserId(userId);
            entity.setBalance(0L);
            entity.setPaidBalance(0L);
            coinAccountMapper.insert(entity);
            return entity;
        });
    }

    /**
     * 乐观锁更新免费币余额.
     *
     * @param userId  用户 ID
     * @param balance 新的免费币余额
     * @param version 当前版本号
     * @return 更新成功返回 true
     */
    public boolean updateFreeBalance(Long userId, Long balance, Integer version) {
        CoinAccountEntity entity = new CoinAccountEntity();
        entity.setUserId(userId);
        entity.setBalance(balance);
        return coinAccountMapper.updateById(entity) > 0;
    }

    /**
     * 乐观锁更新付费币余额.
     *
     * @param userId       用户 ID
     * @param paidBalance  新的付费币余额
     * @param version      当前版本号
     * @return 更新成功返回 true
     */
    public boolean updatePaidBalance(Long userId, Long paidBalance, Integer version) {
        CoinAccountEntity entity = new CoinAccountEntity();
        entity.setUserId(userId);
        entity.setPaidBalance(paidBalance);
        return coinAccountMapper.updateById(entity) > 0;
    }

    /**
     * 乐观锁更新双账户.
     *
     * @param userId       用户 ID
     * @param balance      新的免费币余额
     * @param paidBalance  新的付费币余额
     * @return 更新成功返回 true
     */
    public boolean updateBalance(Long userId, Long balance, Long paidBalance) {
        CoinAccountEntity entity = new CoinAccountEntity();
        entity.setUserId(userId);
        entity.setBalance(balance);
        entity.setPaidBalance(paidBalance);
        int rows = coinAccountMapper.updateById(entity);
        if (rows == 0) {
            throw new OptimisticLockingFailureException("Coin account update failed, version conflict");
        }
        return true;
    }
}
