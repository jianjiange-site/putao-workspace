package com.dating.user.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.user.entity.UserInfoEntity;
import com.dating.user.mapper.UserInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * UserInfo Manager — 单表读写封装,不涉及多表组装.
 *
 * <p>缓存由 {@link UserProfileCacheManager} 负责;本类只做 DB 读写.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInfoManager {

    private final UserInfoMapper userInfoMapper;

    /**
     * 按 userId 查询,deleted=0.
     */
    public UserInfoEntity findByUserId(Long userId) {
        if (userId == null) return null;
        return userInfoMapper.selectByUserId(userId);
    }

    /**
     * 批量按 userId 查询,IN 一次捞,避免 N+1.
     *
     * @param userIds 任意数量的 userId
     * @return 实体列表(顺序不保证)
     */
    public List<UserInfoEntity> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyList();
        return userInfoMapper.selectByUserIds(userIds);
    }

    /**
     * 把 List 转 Map(去重后),供 service 层快速拼接.
     */
    public java.util.Map<Long, UserInfoEntity> mapByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        Set<Long> unique = userIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return findByUserIds(unique).stream()
                .collect(Collectors.toMap(UserInfoEntity::getUserId, e -> e, (a, b) -> a));
    }

    /**
     * 插入新 placeholder 用户.
     *
     * @param entity 必填 userId;其余字段(nickname=User_${userId}, gender=0, pending=1,
     *               regulation_status=0, user_type=1)由 service 层在调用前填好
     */
    public void insertPlaceholder(UserInfoEntity entity) {
        userInfoMapper.insertPlaceholder(entity);
    }

    /**
     * 全量更新;由 service 层保证已传入变更后的字段.
     */
    public void updateById(UserInfoEntity entity) {
        userInfoMapper.updateById(entity);
    }

    /**
     * 用 MyBatis-Plus Wrapper 局部更新(动态 SET,跳 null).
     *
     * <p>由 service 层构造 wrapper,这里只负责执行.
     */
    public int updateSelective(UserInfoEntity entity, LambdaUpdateWrapper<UserInfoEntity> wrapper) {
        return userInfoMapper.update(entity, wrapper);
    }

    /**
     * 命中即 touch last_open_at,ResolveOrCreate 流程里调.
     */
    public int touchLastOpenAt(Long userId) {
        return userInfoMapper.touchLastOpenAt(userId, Instant.now());
    }
}
