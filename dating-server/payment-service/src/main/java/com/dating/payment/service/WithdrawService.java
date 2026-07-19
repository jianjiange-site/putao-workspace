package com.dating.payment.service;

import com.dating.payment.vo.WithdrawApplyVO;
import com.dating.payment.vo.WithdrawHistoryVO;

import java.util.List;

/**
 * 提现服务接口.
 *
 * <p>定义提现模块的核心业务能力（当前为占位实现）.
 */
public interface WithdrawService {

    /**
     * 查询钱包余额.
     *
     * @param userId 用户 ID
     * @return 余额
     */
    long getBalance(Long userId);

    /**
     * 绑定提现账户.
     *
     * @param userId    用户 ID
     * @param channel   通道（PAYPAL/STRIPE/BANK）
     * @param account   账户信息
     */
    void bindAccount(Long userId, String channel, String account);

    /**
     * 申请提现.
     *
     * @param userId  用户 ID
     * @param amount  提现金额
     * @return 提现结果
     */
    WithdrawApplyVO apply(Long userId, long amount);

    /**
     * 查询提现历史.
     *
     * @param userId   用户 ID
     * @param page     页码
     * @param pageSize 每页大小
     * @return 提现记录列表
     */
    List<WithdrawHistoryVO> getHistory(Long userId, int page, int pageSize);
}
