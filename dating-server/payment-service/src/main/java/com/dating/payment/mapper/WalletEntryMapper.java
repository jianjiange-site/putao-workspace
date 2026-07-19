package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.WalletEntryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 钱包流水 Mapper.
 *
 * <p>对应 user_wallet_entries 表.
 */
@Mapper
public interface WalletEntryMapper extends BaseMapper<WalletEntryEntity> {

    /**
     * 分页查询用户钱包流水（按时间倒序）.
     *
     * @param userId  用户 ID
     * @param offset  偏移量
     * @param limit   限制数量
     * @return 流水列表
     */
    @Select("SELECT * FROM user_wallet_entries WHERE user_id = #{userId} ORDER BY created_at DESC OFFSET #{offset} LIMIT #{limit}")
    List<WalletEntryEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 统计用户钱包流水总数.
     *
     * @param userId 用户 ID
     * @return 总数
     */
    @Select("SELECT COUNT(*) FROM user_wallet_entries WHERE user_id = #{userId}")
    int countByUserId(@Param("userId") Long userId);
}
