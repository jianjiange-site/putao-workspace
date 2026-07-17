package com.dating.im.service;

import com.dating.im.entity.UserOnlineSessionEntity;
import com.dating.im.mapper.UserOnlineSessionMapper;
import com.dating.im.manager.PresenceRedisManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 在线状态服务.
 *
 * <p>维护 im:presence:online ZSet 和 user_online_session 表.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final PresenceRedisManager redisManager;
    private final UserOnlineSessionMapper sessionMapper;

    /**
     * 用户上线.
     *
     * @param userId   用户ID
     * @param platform 平台
     * @param onlineAt 上线时刻(epoch ms)
     */
    @Transactional
    public void online(Long userId, Integer platform, long onlineAt) {
        OffsetDateTime utcOnlineAt = toUtc(onlineAt);

        // 1. ZADD NX 标记 Redis 在线
        boolean first = redisManager.markOnline(userId, onlineAt);

        // 2. 首次上线才开 PG 会话
        if (first) {
            UserOnlineSessionEntity session = new UserOnlineSessionEntity();
            session.setUserId(userId);
            session.setPlatform(platform);
            session.setOnlineAt(utcOnlineAt);
            sessionMapper.insert(session);
            log.info("Session started: userId={}, platform={}", userId, platform);
        }
    }

    /**
     * 用户下线.
     *
     * @param userId    用户ID
     * @param platform  平台
     * @param offlineAt 下线时刻(epoch ms)
     */
    @Transactional
    public void offline(Long userId, Integer platform, long offlineAt) {
        // 1. 查 Redis 获取上线时刻
        Optional<Long> sinceOpt = redisManager.onlineSince(userId);

        if (sinceOpt.isPresent()) {
            long since = sinceOpt.get();
            long duration = (offlineAt - since) / 1000; // 秒

            OffsetDateTime utcOfflineAt = toUtc(offlineAt);

            // 2. 回填 PG 会话
            List<UserOnlineSessionEntity> sessions = sessionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOnlineSessionEntity>()
                            .eq(UserOnlineSessionEntity::getUserId, userId)
                            .isNull(UserOnlineSessionEntity::getOfflineAt)
                            .orderByDesc(UserOnlineSessionEntity::getOnlineAt)
                            .last("LIMIT 1")
            );

            if (!sessions.isEmpty()) {
                UserOnlineSessionEntity session = sessions.get(0);
                session.setOfflineAt(utcOfflineAt);
                session.setDurationSeconds((int) duration);
                sessionMapper.updateById(session);
                log.info("Session closed: userId={}, duration={}s", userId, duration);
            }

            // 3. ZREM 移出在线集
            redisManager.markOffline(userId);
        } else {
            log.warn("User not online in Redis: userId={}", userId);
        }
    }

    /**
     * 查询窗口内新上线的用户.
     */
    public List<Long> listOnlineUsers(long since, long until, int limit) {
        return redisManager.listOnlineUsers(since, until, limit);
    }

    /**
     * 查询窗口内已下线的用户.
     */
    public List<Long> listRecentOfflineUsers(long since, long until, int limit) {
        OffsetDateTime sinceUtc = toUtc(since);
        OffsetDateTime untilUtc = toUtc(until);

        List<UserOnlineSessionEntity> sessions = sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOnlineSessionEntity>()
                        .between(UserOnlineSessionEntity::getOfflineAt, sinceUtc, untilUtc)
                        .orderByDesc(UserOnlineSessionEntity::getUserId)
                        .last("LIMIT " + limit)
        );

        return sessions.stream()
                .map(UserOnlineSessionEntity::getUserId)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 孤儿会话清扫(定时任务调用).
     */
    @Transactional
    public void sweepOrphanSessions(int maxOnlineHours) {
        long threshold = System.currentTimeMillis() - (long) maxOnlineHours * 3600 * 1000;

        // 找 score < threshold 的在线会话
        List<UserOnlineSessionEntity> orphans = sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOnlineSessionEntity>()
                        .isNull(UserOnlineSessionEntity::getOfflineAt)
                        .le(UserOnlineSessionEntity::getOnlineAt, OffsetDateTime.ofInstant(
                                Instant.ofEpochMilli(threshold), ZoneOffset.UTC))
        );

        for (UserOnlineSessionEntity session : orphans) {
            long maxMs = (long) maxOnlineHours * 3600 * 1000;
            session.setOfflineAt(OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(session.getOnlineAt().toInstant().toEpochMilli() + maxMs),
                    ZoneOffset.UTC));
            session.setDurationSeconds(maxOnlineHours * 3600);
            sessionMapper.updateById(session);

            redisManager.markOffline(session.getUserId());
            log.info("Orphan session swept: userId={}", session.getUserId());
        }
    }

    private OffsetDateTime toUtc(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
