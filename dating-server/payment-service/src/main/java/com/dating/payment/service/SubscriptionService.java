package com.dating.payment.service;

import com.dating.payment.vo.SubscriptionVO;

/**
 * 订阅服务接口.
 *
 * <p>定义订阅模块的核心业务能力.
 */
public interface SubscriptionService {

    /**
     * 查询用户订阅状态.
     *
     * @param userId 用户 ID
     * @return 订阅信息 VO
     */
    SubscriptionVO getSubscription(Long userId);

    /**
     * 激活/续期订阅（发奖时调用）.
     *
     * @param userId       用户 ID
     * @param tier         档位（2=WEEKLY, 3=MONTHLY, 4=YEARLY）
     * @param durationDays 持续天数
     * @param source       来源
     * @return 新的到期时间
     */
    long activateSubscription(Long userId, int tier, int durationDays, String source);
}
