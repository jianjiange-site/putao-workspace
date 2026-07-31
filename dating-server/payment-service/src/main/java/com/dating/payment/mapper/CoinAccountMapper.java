package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.CoinAccountEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Mapper;

/**
 * 金币账户 Mapper.
 *
 * <p>对应 coin_accounts 表.
 */
@Mapper
public interface CoinAccountMapper extends BaseMapper<CoinAccountEntity> {


    @Insert("""
            INSERT INTO coin_accounts(user_id, balance, paid_balance, version)
            VALUES(#{userId}, 0, 0, 0)
            ON CONFLICT (user_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") Long userId);

    @Select("SELECT * FROM coin_accounts WHERE user_id = #{userId} FOR UPDATE")
    CoinAccountEntity selectByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * 更新已通过 {@link #selectByUserIdForUpdate(Long)} 锁定的账户.
     *
     * <p>{@code version} 仅作为账户修订计数递增，不是乐观锁条件.
     */
    @Update("""
            UPDATE coin_accounts
            SET balance = #{balance},
                paid_balance = #{paidBalance},
                version = version + 1,
                updated_at = NOW()
            WHERE user_id = #{userId}
            """)
    int updateBalances(@Param("userId") Long userId,
                       @Param("balance") Long balance,
                       @Param("paidBalance") Long paidBalance);
}
