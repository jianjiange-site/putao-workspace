package com.dating.payment.controller;

import com.dating.payment.service.WithdrawService;
import com.dating.payment.vo.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 提现 REST Controller.
 *
 * <p>当前为占位实现，业务逻辑待完善.
 */
@Slf4j
@RestController
@RequestMapping("/v1/withdraw")
@RequiredArgsConstructor
public class WithdrawController {

    private final WithdrawService withdrawService;

    /**
     * 查询钱包余额.
     *
     * @param userId 用户 ID
     */
    @GetMapping("/balance")
    public Result<?> getBalance(@RequestParam Long userId) {
        long balance = withdrawService.getBalance(userId);
        return Result.ok(java.util.Map.of("balance", balance));
    }

    /**
     * 绑定提现账户.
     *
     * @param userId  用户 ID
     * @param channel 通道
     * @param account 账户
     */
    @PostMapping("/accounts")
    public Result<?> bindAccount(
            @RequestParam Long userId,
            @RequestParam String channel,
            @RequestParam String account) {
        withdrawService.bindAccount(userId, channel, account);
        return Result.ok(java.util.Map.of("success", true));
    }

    /**
     * 申请提现.
     *
     * @param userId 用户 ID
     * @param amount 金额
     */
    @PostMapping("/request")
    public Result<?> apply(
            @RequestParam Long userId,
            @RequestParam long amount) {
        return Result.ok(withdrawService.apply(userId, amount));
    }

    /**
     * 查询提现历史.
     *
     * @param userId   用户 ID
     * @param page     页码
     * @param pageSize 每页大小
     */
    @GetMapping("/history")
    public Result<?> getHistory(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(withdrawService.getHistory(userId, page, pageSize));
    }
}
