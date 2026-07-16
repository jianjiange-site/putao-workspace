package com.dating.gateway.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Coins VO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinsVO {

    private Long userId;
    
    private Long balance;
}
