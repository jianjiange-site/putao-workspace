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
        validateMutation(userId, amount, key);
        if (key != null && !key.isBlank()) {
            Optional<CoinLedgerEntity> existing = coinLedgerManager.findByIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                log.info("AddCoins idempotent hit: userId={}, key={}", userId, key);
                // 返回新的余额
                return existing.get().getBalanceAfter();
            }
        }

        // 2. 锁定用户账户行；同一用户的金币变更在当前事务中串行执行
        CoinAccountEntity account = coinAccountManager.getOrCreateForUpdate(userId);
        Optional<CoinLedgerEntity> lockedExisting =
                coinLedgerManager.findByIdempotencyKey(userId, key);
        if (lockedExisting.isPresent()) {
            return lockedExisting.get().getBalanceAfter();
        }
        long newBalance = account.getBalance() + amount;

        // 3. 保存流水
        CoinLedgerEntity ledger = buildLedger(userId, CoinLedgerType.INCOME, amount, 0,
                newBalance, 0L, reason, key);
        coinLedgerManager.saveWithIdempotencyCheck(ledger, userId, key);

        // 4. 更新余额
        coinAccountManager.updateBalance(userId, newBalance, account.getPaidBalance());

        log.info("AddCoins success: userId={}, amount={}, newBalance={}", userId, amount, newBalance);
        return newBalance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addPaidCoins(Long userId, int amount, String reason, String key) {
        validateMutation(userId, amount, key);
        // 1. 幂等检查
        if (key != null && !key.isBlank()) {
            Optional<CoinLedgerEntity> existing = coinLedgerManager.findByIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                log.info("AddPaidCoins idempotent hit: userId={}, key={}", userId, key);
                return existing.get().getPaidBalanceAfter();
            }
        }

        // 2. 锁定用户账户行；同一用户的金币变更在当前事务中串行执行
        CoinAccountEntity account = coinAccountManager.getOrCreateForUpdate(userId);
        Optional<CoinLedgerEntity> lockedExisting =
                coinLedgerManager.findByIdempotencyKey(userId, key);
        if (lockedExisting.isPresent()) {
            return lockedExisting.get().getPaidBalanceAfter();
        }
        long newPaidBalance = account.getPaidBalance() + amount;

        // 3. 保存流水
        CoinLedgerEntity ledger = buildLedger(userId, CoinLedgerType.INCOME, 0, amount,
                account.getBalance(), newPaidBalance, reason, key);
        coinLedgerManager.saveWithIdempotencyCheck(ledger, userId, key);

        // 4. 更新余额
        coinAccountManager.updateBalance(userId, account.getBalance(), newPaidBalance);

        log.info("AddPaidCoins success: userId={}, amount={}, newPaidBalance={}",
                userId, amount, newPaidBalance);
        return newPaidBalance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsumeResult consumeCoins(Long userId, int amount, String key, String desc) {
        // 如果金额小于等于 0，则返回错误
        if (amount <= 0) {
            return new ConsumeResult(false, 4001, "Invalid amount", 0);
        }

        if (userId == null || userId <= 0 || key == null || key.isBlank()) {
            return new ConsumeResult(false, 4001, "userId and idempotency key are required", 0);
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

        // 2. 锁定用户账户行；同一用户的扣减在当前事务中串行执行
        CoinAccountEntity account = coinAccountManager.getOrCreateForUpdate(userId);
        Optional<CoinLedgerEntity> lockedExisting =
                coinLedgerManager.findByIdempotencyKey(userId, key);
        if (lockedExisting.isPresent()) {
            CoinLedgerEntity prev = lockedExisting.get();
            return ConsumeResult.ok(
                    prev.getBalanceAfter() + prev.getPaidBalanceAfter());
        }
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
        long newFreeBalance = account.getBalance() - freeTake;
        long newPaidBalance = account.getPaidBalance() - paidTake;

        // 5. 保存流水
        CoinLedgerEntity ledger = buildLedger(userId, CoinLedgerType.EXPENSE,
                -freeTake, -paidTake, newFreeBalance, newPaidBalance, desc, key);
        coinLedgerManager.saveWithIdempotencyCheck(ledger, userId, key);

        // 6. 更新余额
        coinAccountManager.updateBalance(userId, newFreeBalance, newPaidBalance);

        long newTotalBalance = newFreeBalance + newPaidBalance;
        log.info("ConsumeCoins success: userId={}, amount={}, newTotal={}",
                userId, amount, newTotalBalance);
        return ConsumeResult.ok(newTotalBalance);
    }

    private void validateMutation(Long userId, int amount, String key) {
        if (userId == null || userId <= 0) {
            throw new PaymentBizException(4001, "Invalid userId");
        }
        if (amount <= 0) {
            throw new PaymentBizException(4001, "Amount must be positive");
        }
        if (key == null || key.isBlank()) {
            throw new PaymentBizException(4001, "Idempotency key is required");
        }
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
