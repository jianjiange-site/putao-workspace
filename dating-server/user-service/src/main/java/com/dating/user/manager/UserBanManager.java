package com.dating.user.manager;

import com.dating.user.constant.RedisKey;
import com.dating.user.proto.BanReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * UserBan Manager — 封禁状态短缓存(Redis)与运营级封禁 Set 查询.
 *
 * <p>缓存策略:
 * <ul>
 *   <li>{@code user:ban:status:{userId}} String TTL 5min — 用户级 regulation_status
 *       派生结果(0=正常 / 1=banned / 2=suspended)</li>
 *   <li>{@code user:ban:thirdparty-set} Set 永久 — 运营手动维护的封禁集(写入方未定)</li>
 * </ul>
 *
 * <p>CheckBan 调用顺序: 1) 短缓存 → 2) 运营级 Set SISMEMBER →
 * 3) DB regulation_status 派生.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBanManager {

    /** 缓存 key 后缀 */
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_BANNED = "BANNED";
    private static final String STATUS_SUSPENDED = "SUSPENDED";
    private static final String STATUS_OPERATIONAL = "OPERATIONAL";

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 查询封禁状态(优先读短缓存).
     *
     * @param userId 业务主键
     * @return BanReason
     */
    public BanReason queryBanReason(Long userId) {
        if (userId == null) return BanReason.BAN_REASON_NONE;
        String key = RedisKey.banStatus(userId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            return toReason(cached);
        }
        return null; // cache miss,需调用方回源 DB
    }

    /**
     * 写入短缓存(供 UserBanService 在 DB 查询后回填).
     */
    public void cacheBanReason(Long userId, BanReason reason) {
        if (userId == null) return;
        String key = RedisKey.banStatus(userId);
        String value = fromReason(reason);
        stringRedisTemplate.opsForValue().set(key, value, CACHE_TTL);
    }

    /**
     * 命中时清缓存(由 service 在 regulation_status 变化时调).
     */
    public void evictBanStatus(Long userId) {
        if (userId == null) return;
        stringRedisTemplate.delete(RedisKey.banStatus(userId));
    }

    /**
     * 运营级封禁查询.
     *
     * @return 是否在运营封禁 Set 中
     */
    public boolean isOperationalBanned(Long userId) {
        if (userId == null) return false;
        try {
            Boolean hit = stringRedisTemplate.opsForSet()
                    .isMember(RedisKey.banThirdPartySet(), String.valueOf(userId));
            return Boolean.TRUE.equals(hit);
        } catch (Exception e) {
            log.warn("ban thirdparty-set lookup failed, userId={}, err={}", userId, e.getMessage());
            return false;
        }
    }

    private static BanReason toReason(String v) {
        if (v == null) return BanReason.BAN_REASON_NONE;
        return switch (v) {
            case STATUS_BANNED -> BanReason.BAN_REASON_USER_BANNED;
            case STATUS_SUSPENDED -> BanReason.BAN_REASON_USER_SUSPENDED;
            case STATUS_OPERATIONAL -> BanReason.BAN_REASON_OPERATIONAL;
            default -> BanReason.BAN_REASON_NONE;
        };
    }

    private static String fromReason(BanReason r) {
        if (r == null) return STATUS_NORMAL;
        return switch (r) {
            case BAN_REASON_USER_BANNED -> STATUS_BANNED;
            case BAN_REASON_USER_SUSPENDED -> STATUS_SUSPENDED;
            case BAN_REASON_OPERATIONAL -> STATUS_OPERATIONAL;
            default -> STATUS_NORMAL;
        };
    }

    /** 工具: 由 regulation_status DB 值 派生 BanReason */
    public static BanReason reasonFromRegulationStatus(Integer regulationStatus) {
        if (regulationStatus == null) return BanReason.BAN_REASON_NONE;
        // 设计文档 §5.7:DB 2=Banned / 5=Suspended
        if (Objects.equals(regulationStatus, 2)) return BanReason.BAN_REASON_USER_BANNED;
        if (Objects.equals(regulationStatus, 5)) return BanReason.BAN_REASON_USER_SUSPENDED;
        return BanReason.BAN_REASON_NONE;
    }
}
