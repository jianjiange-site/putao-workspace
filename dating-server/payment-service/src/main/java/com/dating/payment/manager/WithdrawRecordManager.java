package com.dating.payment.manager;

import com.dating.payment.entity.WithdrawRecordEntity;
import com.dating.payment.mapper.WithdrawRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 提现记录 Manager.
 *
 * <p>封装提现记录的数据访问操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawRecordManager {

    private final WithdrawRecordMapper withdrawRecordMapper;

    /**
     * 根据提现单号查询.
     *
     * @param withdrawNo 提现单号
     * @return 提现记录
     */
    public Optional<WithdrawRecordEntity> findByWithdrawNo(String withdrawNo) {
        return withdrawRecordMapper.findByWithdrawNo(withdrawNo);
    }

    /**
     * 保存提现记录.
     *
     * @param entity 提现记录实体
     */
    public void save(WithdrawRecordEntity entity) {
        withdrawRecordMapper.insert(entity);
    }

    /**
     * 更新提现记录.
     *
     * @param entity 提现记录实体
     */
    public void updateById(WithdrawRecordEntity entity) {
        withdrawRecordMapper.updateById(entity);
    }

    /**
     * 查询用户最近的提现记录.
     *
     * @param userId  用户 ID
     * @param limit   限制数量
     * @return 提现记录列表
     */
    public List<WithdrawRecordEntity> findRecentByUserId(Long userId, int limit) {
        return withdrawRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WithdrawRecordEntity>()
                        .eq(WithdrawRecordEntity::getUserId, userId)
                        .orderByDesc(WithdrawRecordEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }
}
