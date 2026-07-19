package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.CoinLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 金币流水 Mapper.
 *
 * <p>对应 coin_ledger 表.
 */
@Mapper
public interface CoinLedgerMapper extends BaseMapper<CoinLedgerEntity> {

    /**
     * 根据用户 ID 和幂等键查询流水.
     *
     * @param userId         用户 ID
     * @param idempotencyKey 幂等键
     * @return 流水记录
     */
    @Select("SELECT * FROM coin_ledger WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    Optional<CoinLedgerEntity> findByUserIdAndIdempotencyKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 分页查询用户金币流水（按时间倒序）.
     *
     * @param userId   用户 ID
     * @param offset   偏移量
     * @param limit    限制数量
     * @return 流水列表
     */
    @Select("SELECT * FROM coin_ledger WHERE user_id = #{userId} ORDER BY created_at DESC OFFSET #{offset} LIMIT #{limit}")
    List<CoinLedgerEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 统计用户金币流水总数.
     *
     * @param userId 用户 ID
     * @return 总数
     */
    @Select("SELECT COUNT(*) FROM coin_ledger WHERE user_id = #{userId}")
    int countByUserId(@Param("userId") Long userId);
}
