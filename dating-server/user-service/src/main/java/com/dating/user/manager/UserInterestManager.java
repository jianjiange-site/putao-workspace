package com.dating.user.manager;

import com.dating.user.entity.UserInterestEntity;
import com.dating.user.mapper.UserInterestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserInterest Manager — 兴趣标签表读写.
 *
 * <p>ReplaceUserInterests 由 service 层在事务内调 deleteByUserId + 循环 insert
 * (MyBatis-Plus 自身不提供 batch insert 公开 API,逐行 insert 在小批量场景可接受).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInterestManager {

    private final UserInterestMapper userInterestMapper;

    public List<UserInterestEntity> listByUserId(Long userId) {
        if (userId == null) return Collections.emptyList();
        return userInterestMapper.listByUserId(userId);
    }

    /**
     * 批量按 userIds 一次 IN,避免 N+1.
     *
     * @return Map&lt;userId, List&lt;Interest&gt;&gt;
     */
    public Map<Long, List<UserInterestEntity>> mapByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        return userInterestMapper.listByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserInterestEntity::getUserId));
    }

    public int deleteByUserId(Long userId) {
        return userInterestMapper.deleteByUserId(userId);
    }

    public void insertOne(UserInterestEntity entity) {
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        userInterestMapper.insert(entity);
    }

    /**
     * 批量 INSERT(逐行,依赖事务回滚兜底).
     *
     * @param entities 已经填好的 entity 列表
     */
    public void batchInsert(List<UserInterestEntity> entities) {
        for (UserInterestEntity e : entities) {
            insertOne(e);
        }
    }
}
