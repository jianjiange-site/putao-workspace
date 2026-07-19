package com.dating.payment.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.payment.entity.CoinLedgerEntity;
import com.dating.payment.mapper.CoinLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 金币流水 Manager.
 *
 * <p>封装金币流水的查询操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinLedgerManager {

    private final CoinLedgerMapper coinLedgerMapper;

    /**
     * 根据用户 ID 和幂等键查询流水.
     *
     * @param userId         用户 ID
     * @param idempotencyKey 幂等键
     * @return 流水记录
     */
    public Optional<CoinLedgerEntity> findByIdempotencyKey(Long userId, String idempotencyKey) {
        return coinLedgerMapper.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    /**
     * 分页查询用户金币流水（按时间倒序）.
     *
     * @param userId   用户 ID
     * @param page     页码（1-based）
     * @param pageSize 每页大小
     * @return 流水列表
     */
    public List<CoinLedgerEntity> findByUserIdPaged(Long userId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return coinLedgerMapper.findByUserIdOrderByCreatedAtDesc(userId, offset, pageSize);
    }

    /**
     * 统计用户金币流水总数.
     *
     * @param userId 用户 ID
     * @return 总数
     */
    public int countByUserId(Long userId) {
        return coinLedgerMapper.countByUserId(userId);
    }

    /**
     * 保存金币流水.
     *
     * @param entity 流水实体
     * @throws DuplicateKeyException 幂等键冲突时抛出
     */
    public void save(CoinLedgerEntity entity) {
        coinLedgerMapper.insert(entity);
    }

    /**
     * 保存金币流水（捕获幂等键冲突）.
     *
     * @param entity  流水实体
     * @param userId  用户 ID
     * @param key     幂等键
     * @return 存在则返回已有记录，否则保存并返回
     */
    public CoinLedgerEntity saveWithIdempotencyCheck(CoinLedgerEntity entity, Long userId, String key) {
        try {
            coinLedgerMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            log.warn("Duplicate idempotency key: userId={}, key={}", userId, key);
            return coinLedgerMapper.findByUserIdAndIdempotencyKey(userId, key)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key conflict but record not found"));
        }
    }
}
