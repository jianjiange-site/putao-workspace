package com.dating.payment.service.impl;

import com.dating.payment.constant.PaymentErrorCode;
import com.dating.payment.entity.UserWalletEntity;
import com.dating.payment.exception.PaymentBizException;
import com.dating.payment.manager.UserWalletManager;
import com.dating.payment.service.WithdrawService;
import com.dating.payment.vo.WithdrawApplyVO;
import com.dating.payment.vo.WithdrawHistoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提现服务实现.
 *
 * <p>当前为占位实现，表结构已就位，业务逻辑待完善.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawServiceImpl implements WithdrawService {

    private final UserWalletManager walletManager;

    @Override
    public long getBalance(Long userId) {
        return walletManager.findByUserId(userId)
                .map(UserWalletEntity::getBalance)
                .map(b -> b.longValue())
                .orElse(0L);
    }

    @Override
    public void bindAccount(Long userId, String channel, String account) {
        throw new PaymentBizException(PaymentErrorCode.NOT_IMPLEMENTED, "Withdraw bind account not implemented");
    }

    @Override
    public WithdrawApplyVO apply(Long userId, long amount) {
        throw new PaymentBizException(PaymentErrorCode.NOT_IMPLEMENTED, "Withdraw apply not implemented");
    }

    @Override
    public List<WithdrawHistoryVO> getHistory(Long userId, int page, int pageSize) {
        throw new PaymentBizException(PaymentErrorCode.NOT_IMPLEMENTED, "Withdraw history not implemented");
    }
}
