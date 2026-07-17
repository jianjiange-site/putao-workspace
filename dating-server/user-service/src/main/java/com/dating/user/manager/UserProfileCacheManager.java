package com.dating.user.manager;

import com.dating.user.constant.RedisKey;
import com.dating.user.entity.UserInfoEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * UserProfile 缓存管理器 — "先写库,再删缓存" 写路径.
 *
 * <p>key 规范: {@code user:profile:{userId}} / {@code user:profile:big:{userId}} /
 * {@code user:interest:{userId}}(与设计文档 §5.5 对齐).
 *
 * <p>当前为最小可用实现: GetProfile / GetBatchProfile 走缓存(只读大字段),
 * 写后清空缓存. 批量读暂不缓存(单批最多 200 条,直接 DB IN 一次捞更快).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileCacheManager {

    /** 主资料小字段 TTL */
    private static final Duration PROFILE_TTL = Duration.ofHours(24);
    /** 大字段 TTL */
    private static final Duration PROFILE_BIG_TTL = Duration.ofHours(24);
    /** 兴趣 TTL */
    private static final Duration INTEREST_TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 缓存主资料 JSON 字符串(由 service 层负责序列化 entity → JSON).
     */
    public void cacheProfileJson(Long userId, String json) {
        if (userId == null || json == null) return;
        stringRedisTemplate.opsForValue().set(RedisKey.profile(userId), json, PROFILE_TTL);
    }

    public String getProfileJson(Long userId) {
        if (userId == null) return null;
        return stringRedisTemplate.opsForValue().get(RedisKey.profile(userId));
    }

    public void cacheProfileBigJson(Long userId, String json) {
        if (userId == null || json == null) return;
        stringRedisTemplate.opsForValue().set(RedisKey.profileBig(userId), json, PROFILE_BIG_TTL);
    }

    public String getProfileBigJson(Long userId) {
        if (userId == null) return null;
        return stringRedisTemplate.opsForValue().get(RedisKey.profileBig(userId));
    }

    public void cacheInterestsJson(Long userId, String json) {
        if (userId == null || json == null) return;
        stringRedisTemplate.opsForValue().set(RedisKey.interest(userId), json, INTEREST_TTL);
    }

    public String getInterestsJson(Long userId) {
        if (userId == null) return null;
        return stringRedisTemplate.opsForValue().get(RedisKey.interest(userId));
    }

    /**
     * 写路径统一入口 — 清空与 userId 相关的所有缓存.
     */
    public void evictAll(Long userId) {
        if (userId == null) return;
        stringRedisTemplate.delete(List.of(
                RedisKey.profile(userId),
                RedisKey.profileBig(userId),
                RedisKey.interest(userId)));
    }

    /** 反序列化辅助 */
    public <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("deserialize cache json failed, err={}", e.getMessage());
            return null;
        }
    }

    public <T> T readJson(String json, TypeReference<T> ref) {
        try {
            return objectMapper.readValue(json, ref);
        } catch (Exception e) {
            log.warn("deserialize cache json failed, err={}", e.getMessage());
            return null;
        }
    }

    public Map<String, String> getAll() {
        return Collections.emptyMap();
    }
}
