package com.dating.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.payment.entity.WithdrawRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 提现记录 Mapper.
 *
 * <p>对应 withdraw_records 表.
 */
@Mapper
public interface WithdrawRecordMapper extends BaseMapper<WithdrawRecordEntity> {

    /**
     * 根据提现单号查询.
     *
     * @param withdrawNo 提现单号
     * @return 提现记录
     */
    @Select("SELECT * FROM withdraw_records WHERE withdraw_no = #{withdrawNo} LIMIT 1")
    Optional<WithdrawRecordEntity> findByWithdrawNo(@Param("withdrawNo") String withdrawNo);
}
