package com.dating.payment.controller;

import com.dating.payment.entity.CoinLedgerEntity;
import com.dating.payment.manager.CoinLedgerManager;
import com.dating.payment.service.CoinService;
import com.dating.payment.vo.CoinAccountVO;
import com.dating.payment.vo.CoinLedgerVO;
import com.dating.payment.vo.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 金币 REST Controller.
 */
@Slf4j
@RestController
@RequestMapping("/v1/coins")
@RequiredArgsConstructor
public class CoinController {

    private final CoinService coinService;
    private final CoinLedgerManager ledgerManager;

    /**
     * 查询金币余额.
     *
     * @param userId 用户 ID
     */
    @PostMapping("/balance")
    public Result<CoinAccountVO> getBalance(@RequestParam Long userId) {
        return Result.ok(coinService.getCoins(userId));
    }

    /**
     * 查询金币流水.
     *
     * @param userId   用户 ID
     * @param page     页码（1-based）
     * @param pageSize 每页大小
     */
    @GetMapping("/ledger")
    public Result<CoinLedgerVO.PageResponse> getLedger(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<CoinLedgerEntity> entities = ledgerManager.findByUserIdPaged(userId, page, pageSize);
        int total = ledgerManager.countByUserId(userId);

        List<CoinLedgerVO> entries = entities.stream().map(this::toVO).toList();

        CoinLedgerVO.PageResponse resp = new CoinLedgerVO.PageResponse();
        resp.setCode(0);
        resp.setMessage("OK");
        resp.setEntries(entries);
        resp.setTotal(total);
        resp.setPage(page);
        resp.setPageSize(pageSize);

        return Result.ok(resp);
    }

    /**
     * 增加金币（免费）.
     *
     * @param userId 用户 ID
     * @param amount 数量
     * @param reason 原因
     * @param key    幂等键
     */
    @PostMapping("/add")
    public Result<?> addCoins(
            @RequestParam Long userId,
            @RequestParam int amount,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String key) {
        long balanceAfter = coinService.addCoins(userId, amount, reason, key);
        return Result.ok(java.util.Map.of("balanceAfter", balanceAfter));
    }

    /**
     * 增加付费金币.
     *
     * @param userId 用户 ID
     * @param amount 数量
     * @param reason 原因
     * @param key    幂等键
     */
    @PostMapping("/addPaid")
    public Result<?> addPaidCoins(
            @RequestParam Long userId,
            @RequestParam int amount,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String key) {
        long balanceAfter = coinService.addPaidCoins(userId, amount, reason, key);
        return Result.ok(java.util.Map.of("paidBalanceAfter", balanceAfter));
    }

    /**
     * 扣减金币.
     *
     * @param userId 用户 ID
     * @param amount 数量
     * @param key    幂等键
     * @param desc   描述
     */
    @PostMapping("/consume")
    public Result<?> consumeCoins(
            @RequestParam Long userId,
            @RequestParam int amount,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String desc) {
        CoinService.ConsumeResult result = coinService.consumeCoins(userId, amount, key, desc);
        return Result.ok(java.util.Map.of(
                "success", result.success(),
                "code", result.code(),
                "message", result.message(),
                "balanceAfter", result.balanceAfter()
        ));
    }

    private CoinLedgerVO toVO(CoinLedgerEntity entity) {
        CoinLedgerVO vo = new CoinLedgerVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setType(entity.getType());
        vo.setAmount(entity.getAmount());
        vo.setPaidAmount(entity.getPaidAmount());
        vo.setBalanceAfter(entity.getBalanceAfter());
        vo.setPaidBalanceAfter(entity.getPaidBalanceAfter());
        vo.setReason(entity.getReason());
        vo.setExtra(entity.getExtra());
        vo.setCreatedAt(entity.getCreatedAt().toEpochMilli());
        return vo;
    }
}
