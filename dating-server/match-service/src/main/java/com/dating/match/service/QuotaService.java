package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.SubscriptionTierConst;
import com.dating.match.exception.MatchBizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 当日配额服务 — Redis HASH 原子计数.
 *
 * <p>key 格式:{@code putao:match:quota:&lt;user_id&gt;:&lt;yyyymmdd&gt;},
 * 字段:right_swipe / cards / super_hi.
 *
 * <p>配额上限由订阅档位决定(参 {@link SubscriptionTierConst}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;
    private final MatchProperties props;

    /** 当日 keys 标记 — 用于一次性设置 TTL. */
    private static final String F_RIGHT = "right_swipe";
    private static final String F_CARDS = "cards";
    private static final String F_SUPER_HI = "super_hi";

    /**
     * 预检查 + 原子累加右划配额.
     *
     * <p>顺序:HINCRBY right_swipe → 超限 HINCRBY -1 回滚 + 抛错.
     * 同时累加 cards 字段.
     *
     * @param tier 用户订阅档位
     * @return true=消费成功;false=未消费(已超限,异常已抛)
     * @throws MatchBizException QUOTA_RIGHT_SWIPE_EXCEEDED / QUOTA_CARDS_EXCEEDED
     */
    public void consumeRightSwipe(long userId, int tier) {
        String key = quotaKey(userId);
        int rightLimit = SubscriptionTierConst.dailyRightSwipeLimit(tier);
        int cardLimit = SubscriptionTierConst.dailyCardLimit(tier);

        // 1. right_swipe
        Long right = stringRedisTemplate.opsForHash().increment(key, F_RIGHT, 1L);
        // 首次写入时设 TTL
        if (right != null && right == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(props.getQuotaTtlSeconds()));
        }
        if (right != null && right > rightLimit) {
            stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
            throw new MatchBizException(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED,
                    MatchErrorCode.getMessage(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED));
        }

        // 2. cards
        Long cards = stringRedisTemplate.opsForHash().increment(key, F_CARDS, 1L);
        if (cards != null && cards > cardLimit) {
            // cards 已超限,回滚两张计数
            stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
            stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
            throw new MatchBizException(MatchErrorCode.QUOTA_CARDS_EXCEEDED,
                    MatchErrorCode.getMessage(MatchErrorCode.QUOTA_CARDS_EXCEEDED));
        }
    }

    /**
     * 预检查 + 原子累加 Super Hi(订阅赠送 / 金币两种路径,统一扣 cards + right_swipe + super_hi).
     *
     * <p>先扣 cards(基础消耗),再扣 right_swipe(Super Hi 本质是高优先级喜欢),最后扣 super_hi 赠送配额.
     *
     * @return 0=订阅赠送成功 / >0=用了 N 个金币
     */
    public SuperHiCharge consumeSuperHi(long userId, int tier, int giftedLimit, int coinPrice) {
        String key = quotaKey(userId);
        // 获取用户订阅等级
        // 获取每日右划限制
        int rightLimit = SubscriptionTierConst.dailyRightSwipeLimit(tier);
        // 获取每日卡片限制
        int cardLimit = SubscriptionTierConst.dailyCardLimit(tier);

        // 1. cards
        // 累加卡片计数
        Long cards = stringRedisTemplate.opsForHash().increment(key, F_CARDS, 1L);
        // 如果卡片计数为 1，设置 TTL
        if (cards != null && cards == 1L) {
            // 设置 TTL
            stringRedisTemplate.expire(key, Duration.ofSeconds(props.getQuotaTtlSeconds()));
        }
        // 如果卡片计数大于卡片限制，回滚卡片计数，抛出异常
        if (cards != null && cards > cardLimit) {
            // 回滚卡片计数
            stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
            // 回滚右划计数
            stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
            // 抛出异常
            throw new MatchBizException(MatchErrorCode.QUOTA_CARDS_EXCEEDED,
                    MatchErrorCode.getMessage(MatchErrorCode.QUOTA_CARDS_EXCEEDED));
        }

        // 2. right_swipe
        // 累加右划计数
        Long right = stringRedisTemplate.opsForHash().increment(key, F_RIGHT, 1L);
        // 如果右划计数大于右划限制，回滚右划计数，抛出异常
        if (right != null && right > rightLimit) {
            // 回滚右划计数
            stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
            // 回滚卡片计数
            stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
            // 抛出异常
            throw new MatchBizException(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED,
                    MatchErrorCode.getMessage(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED));
        }

        // 3. super_hi 赠送配额(0 = 无赠送 / 必须金币)
        // 累加赠送计数
        Long gifted = stringRedisTemplate.opsForHash().increment(key, F_SUPER_HI, 1L);
        // 如果赠送计数没超过赠送限制，返回 SuperHiCharge 实体，不需要金币
        if (gifted != null && gifted <= giftedLimit) {
            // 返回 SuperHiCharge 实体
            return new SuperHiCharge(0, false);
        }
        // 超赠送 — 回滚 super_hi,标记走金币
        stringRedisTemplate.opsForHash().increment(key, F_SUPER_HI, -1L);
        return new SuperHiCharge(coinPrice, true);
    }

    /**
     * 回滚配额(Super Hi 用金币失败时补偿回滚).
     */
    public void rollbackSuperHi(long userId) {
        String key = quotaKey(userId);
        stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
        stringRedisTemplate.opsForHash().increment(key, F_RIGHT, -1L);
    }

    /**
     * 只扣 cards(LEFT_SWIPE 不消耗 right_swipe).
     */
    public void consumeCardOnly(long userId, int tier) {
        String key = quotaKey(userId);
        int cardLimit = SubscriptionTierConst.dailyCardLimit(tier);
        Long cards = stringRedisTemplate.opsForHash().increment(key, F_CARDS, 1L);
        if (cards != null && cards == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(props.getQuotaTtlSeconds()));
        }
        if (cards != null && cards > cardLimit) {
            stringRedisTemplate.opsForHash().increment(key, F_CARDS, -1L);
            throw new MatchBizException(MatchErrorCode.QUOTA_CARDS_EXCEEDED,
                    MatchErrorCode.getMessage(MatchErrorCode.QUOTA_CARDS_EXCEEDED));
        }
    }

    /**
     * 当前配额快照(用于 GetQuota RPC).
     */
    public QuotaSnapshot snapshot(long userId, int tier) {
        String key = quotaKey(userId);
        Object right = stringRedisTemplate.opsForHash().get(key, F_RIGHT);
        Object cards = stringRedisTemplate.opsForHash().get(key, F_CARDS);
        Object sh = stringRedisTemplate.opsForHash().get(key, F_SUPER_HI);
        return new QuotaSnapshot(
                SubscriptionTierConst.dailyRightSwipeLimit(tier),
                toInt(right),
                SubscriptionTierConst.dailyCardLimit(tier),
                toInt(cards),
                SubscriptionTierConst.dailySuperHiLimit(tier),
                toInt(sh),
                SubscriptionTierConst.SUPER_HI_COIN_PRICE,
                tier
        );
    }

    /**
     * 判断当日卡片配额是否耗尽.
     */
    public boolean isCardsExhausted(long userId, int tier) {
        QuotaSnapshot s = snapshot(userId, tier);
        return s.dailyCardUsed() >= s.dailyCardLimit();
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 当日 yyyymmdd(UTC). */
    public static String todayUtc() {
        return LocalDate.now(ZoneOffset.UTC).format(YYYYMMDD);
    }

    /** 完整 quota key. */
    public static String quotaKey(long userId) {
        return "putao:match:quota:" + userId + ":" + todayUtc();
    }

    /** Super Hi 扣减结果(coinsUsed + needCoinCharge). */
    public record SuperHiCharge(int coinsUsed, boolean needCoinCharge) {
    }

    /** 配额快照. */
    public record QuotaSnapshot(
            int dailyRightSwipeLimit,
            int dailyRightSwipeUsed,
            int dailyCardLimit,
            int dailyCardUsed,
            int dailySuperHiLimit,
            int dailySuperHiUsed,
            int superHiCoinPrice,
            int tier
    ) {
    }
}