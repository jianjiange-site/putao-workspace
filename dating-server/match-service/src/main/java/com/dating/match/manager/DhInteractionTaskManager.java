package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.DhInteractionTaskEntity;
import com.dating.match.mapper.DhInteractionTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * dh_interaction_task Manager — 短生命周期,执行后硬删.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DhInteractionTaskManager {

    private final DhInteractionTaskMapper taskMapper;

    /**
     * 批量写入任务.
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchInsert(List<DhInteractionTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (DhInteractionTaskEntity t : tasks) {
            taskMapper.insert(t);
        }
    }

    /**
     * 扫描到期待执行任务.
     */
    public List<DhInteractionTaskEntity> scanDueTasks(Instant now, int limit) {
        return taskMapper.scanDueTasks(now, limit);
    }

    /**
     * 是否已有某 scene 的未执行任务(generator 去重闸).
     */
    public boolean existsByScene(long userId, int scene) {
        return taskMapper.existsByScene(userId, scene) != null;
    }

    /**
     * 单条硬删.
     */
    public int hardDelete(Long id) {
        return taskMapper.hardDelete(id);
    }

    /**
     * 积压监控.
     */
    public long countOverdue(Instant threshold) {
        return taskMapper.countOverdue(threshold);
    }
}