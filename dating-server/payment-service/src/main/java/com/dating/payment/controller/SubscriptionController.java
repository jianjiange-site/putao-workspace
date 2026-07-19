package com.dating.payment.controller;

import com.dating.payment.service.SubscriptionService;
import com.dating.payment.vo.Result;
import com.dating.payment.vo.SubscriptionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 订阅 REST Controller.
 */
@Slf4j
@RestController
@RequestMapping("/v1/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    /**
     * 查询订阅状态.
     *
     * @param userId 用户 ID
     */
    @PostMapping
    public Result<SubscriptionVO> getSubscription(@RequestParam Long userId) {
        log.info("getSubscription: userId={}", userId);
        return Result.ok(subscriptionService.getSubscription(userId));
    }
}
