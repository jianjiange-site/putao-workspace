package com.dating.payment.service;

import com.dating.payment.vo.CoinAccountVO;

/**
 * 金币服务接口.
 *
 * <p>定义金币模块的核心业务能力.
 */
public interface CoinService {

    /**
     * 查询金币余额.
     *
     * @param userId 用户 ID
     * @return 金币账户 VO
     */
    CoinAccountVO getCoins(Long userId);

    /**
     * 增加免费金币.
     *
     * @param userId  用户 ID
     * @param amount  增加数量
     * @param reason  原因
     * @param key     幂等键（可为空）
     * @return 变动后余额
     */
    long addCoins(Long userId, int amount, String reason, String key);

    /**
     * 增加付费金币（充值/发奖走这个）.
     *
     * @param userId  用户 ID
     * @param amount  增加数量
     * @param reason  原因
     * @param key     幂等键（可为空）
     * @return 变动后付费余额
     */
    long addPaidCoins(Long userId, int amount, String reason, String key);

    /**
     * 扣减金币（先扣免费，不够再扣付费）.
     *
     * @param userId  用户 ID
     * @param amount  扣减数量
     * @param key     幂等键（可为空）
     * @param desc    描述
     * @return 扣减结果
     */
    ConsumeResult consumeCoins(Long userId, int amount, String key, String desc);

    /**
     * 扣减结果.
     */
    record ConsumeResult(boolean success, int code, String message, long balanceAfter) {
        public static ConsumeResult ok(long balanceAfter) {
            return new ConsumeResult(true, 0, "OK", balanceAfter);
        }

        public static ConsumeResult insufficient() {
            return new ConsumeResult(false, 3001, "Insufficient coins", 0);
        }
    }
}
