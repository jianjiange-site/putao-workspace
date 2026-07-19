package com.dating.payment.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 金币流水 VO.
 */
@Data
@Accessors(chain = true)
public class CoinLedgerVO {
    private Long id;
    private Long userId;
    private String type;           // INCOME/EXPENSE
    private long amount;           // 免费币变动量
    private long paidAmount;       // 付费币变动量
    private long balanceAfter;     // 变动后免费余额
    private long paidBalanceAfter; // 变动后付费余额
    private String reason;
    private String extra;
    private long createdAt;

    /** 分页响应 */
    @Data
    public static class PageResponse {
        private int code;
        private String message;
        private List<CoinLedgerVO> entries;
        private int total;
        private int page;
        private int pageSize;
    }
}
