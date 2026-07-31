package com.dating.payment.manager;

import com.dating.payment.entity.CoinAccountEntity;
import com.dating.payment.mapper.CoinAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 金币账户 Manager.
 *
 * <p>封装金币账户的数据访问，并通过数据库行锁串行化同一用户的余额变更.
 */
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
        coinAccountMapper.insertIfAbsent(userId);
        return findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Coin account initialization failed: " + userId));
    }

    /**
     * 确保账户存在，并在当前事务中锁定用户账户行.
     *
     * <p>调用方必须处于事务中，行锁会在事务提交或回滚时释放.
     */
    public CoinAccountEntity getOrCreateForUpdate(Long userId) {
        coinAccountMapper.insertIfAbsent(userId);
        CoinAccountEntity account = coinAccountMapper.selectByUserIdForUpdate(userId);
        if (account == null) {
            throw new IllegalStateException("Coin account lock failed: " + userId);
        }
        return account;
    }

    /**
     * 更新已由当前事务锁定的金币账户.
     *
     * @param userId       用户 ID
     * @param balance      新的免费币余额
     * @param paidBalance  新的付费币余额
     * @return 更新成功返回 true
     */
    public boolean updateBalance(Long userId, Long balance, Long paidBalance) {
        int rows = coinAccountMapper.updateBalances(userId, balance, paidBalance);
        if (rows != 1) {
            throw new IllegalStateException(
                    "Coin account update affected unexpected rows: " + rows);
        }
        return true;
    }
}
