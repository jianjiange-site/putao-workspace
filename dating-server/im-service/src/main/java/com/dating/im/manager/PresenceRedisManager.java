package com.dating.im.manager;

import com.dating.im.constant.ImRedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis 在线状态管理器.
 *
 * <p>维护 im:presence:online ZSet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceRedisManager {

    private final StringRedisTemplate redisTemplate;

    /**
     * 标记用户上线.
     *
     * @param userId 用户ID
     * @param onlineAt 上线时刻(epoch ms)
     * @return true=首次上线 / false=已在线
     */
    public boolean markOnline(Long userId, long onlineAt) {
        String key = ImRedisKey.presenceOnline();
        // ZADD NX: 仅当 member 不存在时才添加
        redisTemplate.opsForZSet().addIfAbsent(key, userId.toString(), onlineAt);

        // 检查是否真的添加了(已在线的不会重复添加)
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        boolean first = score != null && score.longValue() == onlineAt;

        if (first) {
            log.info("User online: userId={}, at={}", userId, onlineAt);
        } else {
            log.debug("User already online: userId={}", userId);
        }
        return first;
    }

    /**
     * 获取用户上线时刻.
     *
     * @param userId 用户ID
     * @return 上线时刻 epoch ms, null=不在线
     */
    public Optional<Long> onlineSince(Long userId) {
        String key = ImRedisKey.presenceOnline();
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        return score != null ? Optional.of(score.longValue()) : Optional.empty();
    }

    /**
     * 标记用户下线.
     *
     * @param userId 用户ID
     */
    public void markOffline(Long userId) {
        String key = ImRedisKey.presenceOnline();
        redisTemplate.opsForZSet().remove(key, userId.toString());
        log.info("User offline: userId={}", userId);
    }

    /**
     * 查询窗口内新上线的用户.
     *
     * @param since 开始时刻(epoch ms)
     * @param until 结束时刻(epoch ms)
     * @param limit 最大返回数
     * @return userId 列表
     */
    public List<Long> listOnlineUsers(long since, long until, int limit) {
        String key = ImRedisKey.presenceOnline();
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .rangeByScoreWithScores(key, since, until, 0, limit);

        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        return tuples.stream()
                .map(ZSetOperations.TypedTuple::getValue)
                .filter(this::isNumericUserId)
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    /**
     * 查询当前在线用户数.
     */
    public long onlineCount() {
        String key = ImRedisKey.presenceOnline();
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count != null ? count : 0L;
    }

    private boolean isNumericUserId(String id) {
        try {
            Long.parseLong(id);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
