package com.dating.gateway.vo;

/**
 * Coins VO.
 */
public class CoinsVO {

    private Long userId;
    private Long balance;

    public CoinsVO() {}

    public CoinsVO(Long userId, Long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }

    public static CoinsVOBuilder builder() {
        return new CoinsVOBuilder();
    }

    public static class CoinsVOBuilder {
        private Long userId;
        private Long balance;

        public CoinsVOBuilder userId(Long userId) { this.userId = userId; return this; }
        public CoinsVOBuilder balance(Long balance) { this.balance = balance; return this; }
        public CoinsVO build() {
            CoinsVO vo = new CoinsVO();
            vo.setUserId(userId);
            vo.setBalance(balance);
            return vo;
        }
    }
}
