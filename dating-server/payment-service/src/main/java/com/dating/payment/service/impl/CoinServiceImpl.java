package com.dating.payment.service.impl;

import com.dating.payment.constant.CoinLedgerType;
import com.dating.payment.entity.CoinAccountEntity;
import com.dating.payment.entity.CoinLedgerEntity;
import com.dating.payment.exception.PaymentBizException;
import com.dating.payment.manager.CoinAccountManager;
import com.dating.payment.manager.CoinLedgerManager;
import com.dating.payment.service.CoinService;
import com.dating.payment.vo.CoinAccountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 金币服务实现.
 *
 * <p>完整实现免费/付费双账户，扣减先免费后付费，支持幂等.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinServiceImpl implements CoinService {

    private final CoinAccountManager coinAccountManager;
    private final CoinLedgerManager coinLedgerManager;

    @Override
    public CoinAccountVO getCoins(Long userId) {
        CoinAccountEntity account = coinAccountManager.getOrCreate(userId);
        CoinAccountVO vo = new CoinAccountVO();
        vo.setUserId(userId);
        vo.setBalance(account.getBalance());
        vo.setPaidBalance(account.getPaidBalance());
        vo.setTotalBalance(account.getBalance() + account.getPaidBalance());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addCoins(Long userId, int amount, String reason, String key) {
        // 1. 幂等检查
        if (key != null && !key.isBlank()) {
            Optional<CoinLedgerEntity> existing = coinLedgerManager.findByIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                log.info("AddCoins idempotent hit: userId={}, key={}", userId, key);
                // 返回新的余额
                return existing.get().getBalanceAfter();
            }
        }

        // 2. 循环重试乐观锁更新
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                // 获取或创建账户
                CoinAccountEntity account = coinAccountManager.getOrCreate(userId);
                // 计算新余额
                long newBalance = account.getBalance() + amount;

                // 3. 保存流水
                CoinLedgerEntity ledger = buildLedger(userId, CoinLedgerType.INCOME, amount, 0,
                        newBalance, 0L, reason, key);
                // 保存流水
                ledger = coinLedgerManager.saveWithIdempotencyCheck(ledger, userId, key);

                // 4. 更新余额
                account.setBalance(newBalance);
                coinAccountManager.updateBalance(userId, newBalance, account.getPaidBalance());

                log.info("AddCoins success: userId={}, amount={}, newBalance={}", userId, amount, newBalance);
                return newBalance;
            } catch (OptimisticLockingFailureException e) {
                retryCount++;
                log.warn("AddCoins optimistic lock retry: userId={}, retry={}", userId, retryCount);
                if (retryCount >= 3) {
                    throw new PaymentBizException(500, "Add coins failed after retries");
                }
            }
        }
        throw new PaymentBizException(500, "Add coins failed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addPaidCoins(Long userId, int amount, String reason, String key) {
        // 1. 幂等检查
        if (key != null && !key.isBlank()) {
            Optional<CoinLedgerEntity> existing = coinLedgerManager.findByIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                log.info("AddPaidCoins idempotent hit: userId={}, key={}", userId, key);
                return existing.get().getPaidBalanceAfter();
            }
        }

        // 2. 循环重试乐观锁更新
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                CoinAccountEntity account = coinAccountManager.getOrCreate(userId);
                long newPaidBalance = account.getPaidBalance() + amount;

                // 3. 保存流水
                CoinLedgerEntity ledger = buildLedger(userId, CoinLedgerType.INCOME, 0, amount,
                        account.getBalance(), newPaidBalance, reason, key);
                ledger = coinLedgerManager.saveWithIdempotencyCheck(ledger, userId, key);

                // 4. 更新余额
                coinAccountManager.updateBalance(userId, account.getBalance(), newPaidBalance);

                log.info("AddPaidCoins success: userId={}, amount={}, newPaidBalance={}", userId, amount, newPaidBalance);
                return newPaidBalance;
            } catch (OptimisticLockingFailureException e) {
                retryCount++;
                log.warn("AddPaidCoins optimistic lock retry: userId={}, retry={}", userId, retryCount);
                if (retryCount >= 3) {
                    throw new PaymentBizException(500, "Add paid coins failed after retries");
                }
            }
        }
        throw new PaymentBizException(500, "Add paid coins failed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consumeCoins(Long userId, int amount, String key, String desc) {
        // 如果金额小于等于 0，则返回错误
        if (amount <= 0) {
            return new ConsumeResult(false, 4001, "Invalid amount", 0);
        }

        // 1. 幂等检查
        if (key != null && !key.isBlank()) {
            // 查找幂等键
            Optional<CoinLedgerEntity> existing = coinLedgerManager.findByIdempotencyKey(userId, key);
            // 如果存在，则返回之前的余额
            if (existing.isPresent()) {
                log.info("ConsumeCoins idempotent hit: userId={}, key={}", userId, key);
                CoinLedgerEntity prev = existing.get();
                long totalBalance = prev.getBalanceAfter() + prev.getPaidBalanceAfter();
                return ConsumeResult.ok(totalBalance);
            }
        }

        // 2. 循环重试乐观锁更新
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                // 获取或创建账户
                CoinAccountEntity account = coinAccountManager.getOrCreate(userId);
                // 计算总余额
                long totalBalance = account.getBalance() + account.getPaidBalance();

                // 3. 余额不足
                if (totalBalance < amount) {
                    log.warn("ConsumeCoins insufficient: userId={}, balance={}, required={}",
                            userId, totalBalance, amount);
                    return ConsumeResult.insufficient();
                }

                // 4. 计算扣减顺序：先扣免费，再扣付费
                long freeTake = Math.min(amount, account.getBalance());
                long paidTake = amount - freeTake;

                // 计算新免费余额
                long newFreeBalance = account.getBalance() - freeTake;
                // 计算新付费余额
                long newPaidBalance = account.getPaidBalance() - paidTake;

                // 5. 保存流水
                // 构建流水，参数（用户 ID，类型，免费金额，付费金额，新免费余额，新付费余额，描述，幂等键）
                CoinLedgerEntity ledger = buildLedger(userId, CoinLedgerType.EXPENSE,
                        -freeTake, -paidTake, newFreeBalance, newPaidBalance, desc, key);
                // 保存流水
                ledger = coinLedgerManager.saveWithIdempotencyCheck(ledger, userId, key);

                // 6. 更新余额，参数（用户 ID，免费余额，付费余额）
                coinAccountManager.updateBalance(userId, newFreeBalance, newPaidBalance);

                // 计算新总余额
                long newTotalBalance = newFreeBalance + newPaidBalance;
                // 记录日志
                log.info("ConsumeCoins success: userId={}, amount={}, newTotal={}", userId, amount, newTotalBalance);
                return ConsumeResult.ok(newTotalBalance);
            } catch (OptimisticLockingFailureException e) {
                retryCount++;
                log.warn("ConsumeCoins optimistic lock retry: userId={}, retry={}", userId, retryCount);
                if (retryCount >= 3) {
                    throw new PaymentBizException(500, "Consume coins failed after retries");
                }
            }
        }
        throw new PaymentBizException(500, "Consume coins failed");
    }

    private CoinLedgerEntity buildLedger(Long userId, String type, long amount, long paidAmount,
                                          long balanceAfter, long paidBalanceAfter, String reason, String key) {
        CoinLedgerEntity ledger = new CoinLedgerEntity();
        ledger.setUserId(userId);
        ledger.setType(type);
        ledger.setAmount(amount);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setPaidAmount(paidAmount);
        ledger.setPaidBalanceAfter(paidBalanceAfter);
        ledger.setReason(reason);
        ledger.setIdempotencyKey(key != null && !key.isBlank() ? key : null);
        return ledger;
    }
}
