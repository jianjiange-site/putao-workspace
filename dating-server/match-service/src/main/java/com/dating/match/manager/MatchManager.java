package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.MatchEntity;
import com.dating.match.mapper.MatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Match 主表 Manager.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchManager {

    private final MatchMapper matchMapper;

    /**
     * 根据 (low, high) 查询.
     */
    public Optional<MatchEntity> findByPair(long low, long high) {
        return Optional.ofNullable(matchMapper.findByPair(low, high));
    }

    /**
     * INSERT IGNORE — 触发 UNIQUE 约束时返回已存在记录(由调用方处理日志).
     *
     * <p>同事务由调用方负责.
     *
     * @return Optional.of(new entity) OR Optional.of(existing) — 两个都有值;非空 Optional.
     */
    public Optional<MatchEntity> insertIgnoreConflict(MatchEntity entity) {
        long low = entity.getUserIdLow();
        long high = entity.getUserIdHigh();
        try {
            if (entity.getMatchedAt() == null) {
                entity.setMatchedAt(Instant.now());
            }
            matchMapper.insert(entity);
            return Optional.of(entity);
        } catch (DuplicateKeyException e) {
            // UNIQUE 冲突 — 已存在
            MatchEntity existing = matchMapper.findByPair(low, high);
            return Optional.ofNullable(existing);
        }
    }

    /**
     * 插入(无 IGNORE,冲突会抛异常).
     */
    public void insert(MatchEntity entity) {
        if (entity.getMatchedAt() == null) {
            entity.setMatchedAt(Instant.now());
        }
        matchMapper.insert(entity);
    }

    /**
     * 分页查询某用户的 match(按 matched_at DESC).
     */
    public List<MatchEntity> listByUser(long userId, int limit) {
        return matchMapper.listByUser(userId, limit);
    }

    /**
     * 简单按 match_id 查.
     */
    public Optional<MatchEntity> findById(Long matchId) {
        return Optional.ofNullable(matchMapper.selectById(matchId));
    }

    /**
     * 双方 user_id 计算 (low, high).
     */
    public static long[] pair(long a, long b) {
        return a < b ? new long[]{a, b} : new long[]{b, a};
    }
}
