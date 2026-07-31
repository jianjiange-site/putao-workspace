package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.SubscriptionTierConst;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.exception.MatchBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Daily quota counters implemented as single-command Redis Lua transitions. */
@Service
@RequiredArgsConstructor
public class QuotaService {
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String F_RIGHT = "right_swipe";
    private static final String F_CARDS = "cards";
    private static final String F_SUPER_HI = "super_hi";

    private static final DefaultRedisScript<Long> CONSUME_RIGHT = script("""
            local right = tonumber(redis.call('HGET', KEYS[1], 'right_swipe') or '0')
            local cards = tonumber(redis.call('HGET', KEYS[1], 'cards') or '0')
            if right + 1 > tonumber(ARGV[1]) then return 1 end
            if cards + 1 > tonumber(ARGV[2]) then return 2 end
            redis.call('HINCRBY', KEYS[1], 'right_swipe', 1)
            redis.call('HINCRBY', KEYS[1], 'cards', 1)
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return 0
            """);

    private static final DefaultRedisScript<Long> CONSUME_CARD = script("""
            local cards = tonumber(redis.call('HGET', KEYS[1], 'cards') or '0')
            if cards + 1 > tonumber(ARGV[1]) then return 2 end
            redis.call('HINCRBY', KEYS[1], 'cards', 1)
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """);

    private static final DefaultRedisScript<Long> CONSUME_SUPER_HI = script("""
            local previous = redis.call('HGET', KEYS[1], ARGV[6])
            if previous then return tonumber(previous) end
            local right = tonumber(redis.call('HGET', KEYS[1], 'right_swipe') or '0')
            local cards = tonumber(redis.call('HGET', KEYS[1], 'cards') or '0')
            local gifted = tonumber(redis.call('HGET', KEYS[1], 'super_hi') or '0')
            if right + 1 > tonumber(ARGV[1]) then return -1 end
            if cards + 1 > tonumber(ARGV[2]) then return -2 end
            redis.call('HINCRBY', KEYS[1], 'right_swipe', 1)
            redis.call('HINCRBY', KEYS[1], 'cards', 1)
            local result = tonumber(ARGV[4])
            if gifted < tonumber(ARGV[3]) then
                redis.call('HINCRBY', KEYS[1], 'super_hi', 1)
                result = 0
            end
            redis.call('HSET', KEYS[1], ARGV[6], tostring(result))
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[5]) end
            return result
            """);

    private static final DefaultRedisScript<Long> ROLLBACK_SUPER_HI = script("""
            local value = redis.call('HGET', KEYS[1], ARGV[1])
            if not value then return 0 end
            redis.call('HDEL', KEYS[1], ARGV[1])
            local cards = tonumber(redis.call('HGET', KEYS[1], 'cards') or '0')
            local right = tonumber(redis.call('HGET', KEYS[1], 'right_swipe') or '0')
            if cards > 0 then redis.call('HINCRBY', KEYS[1], 'cards', -1) end
            if right > 0 then redis.call('HINCRBY', KEYS[1], 'right_swipe', -1) end
            if tonumber(value) == 0 then
                local gifted = tonumber(redis.call('HGET', KEYS[1], 'super_hi') or '0')
                if gifted > 0 then redis.call('HINCRBY', KEYS[1], 'super_hi', -1) end
            end
            return 1
            """);

    private static final DefaultRedisScript<Long> ROLLBACK_SWIPE = script("""
            local cards = tonumber(redis.call('HGET', KEYS[1], 'cards') or '0')
            if cards > 0 then redis.call('HINCRBY', KEYS[1], 'cards', -1) end
            if ARGV[1] == '1' then
                local right = tonumber(redis.call('HGET', KEYS[1], 'right_swipe') or '0')
                if right > 0 then redis.call('HINCRBY', KEYS[1], 'right_swipe', -1) end
            end
            return 1
            """);

    private final StringRedisTemplate redis;
    private final MatchProperties props;

    public void consumeRightSwipe(long userId, int tier) {
        Long code = redis.execute(CONSUME_RIGHT, List.of(quotaKey(userId)),
                String.valueOf(SubscriptionTierConst.dailyRightSwipeLimit(tier)),
                String.valueOf(SubscriptionTierConst.dailyCardLimit(tier)),
                String.valueOf(props.getQuotaTtlSeconds()));
        checkQuotaCode(code);
    }

    public void consumeCardOnly(long userId, int tier) {
        Long code = redis.execute(CONSUME_CARD, List.of(quotaKey(userId)),
                String.valueOf(SubscriptionTierConst.dailyCardLimit(tier)),
                String.valueOf(props.getQuotaTtlSeconds()));
        checkQuotaCode(code);
    }

    public SuperHiCharge consumeSuperHi(long userId, int tier, int giftedLimit,
                                         int coinPrice, String operationKey) {
        Long result = redis.execute(CONSUME_SUPER_HI, List.of(quotaKey(userId)),
                String.valueOf(SubscriptionTierConst.dailyRightSwipeLimit(tier)),
                String.valueOf(SubscriptionTierConst.dailyCardLimit(tier)),
                String.valueOf(giftedLimit),
                String.valueOf(coinPrice),
                String.valueOf(props.getQuotaTtlSeconds()),
                operationField(operationKey));
        if (result == null) {
            throw new IllegalStateException("Redis quota script returned null");
        }
        if (result == -1L) {
            throw quotaError(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED);
        }
        if (result == -2L) {
            throw quotaError(MatchErrorCode.QUOTA_CARDS_EXCEEDED);
        }
        return new SuperHiCharge(result.intValue(), result > 0);
    }

    public void rollbackSuperHi(long userId, String operationKey) {
        redis.execute(ROLLBACK_SUPER_HI, List.of(quotaKey(userId)), operationField(operationKey));
    }

    public void rollbackSwipe(long userId, int direction) {
        redis.execute(ROLLBACK_SWIPE, List.of(quotaKey(userId)),
                direction == SwipeDirectionConst.RIGHT ? "1" : "0");
    }

    public QuotaSnapshot snapshot(long userId, int tier) {
        Map<Object, Object> values = redis.opsForHash().entries(quotaKey(userId));
        return new QuotaSnapshot(
                SubscriptionTierConst.dailyRightSwipeLimit(tier), toInt(values.get(F_RIGHT)),
                SubscriptionTierConst.dailyCardLimit(tier), toInt(values.get(F_CARDS)),
                SubscriptionTierConst.dailySuperHiLimit(tier), toInt(values.get(F_SUPER_HI)),
                SubscriptionTierConst.SUPER_HI_COIN_PRICE, tier);
    }

    public boolean isCardsExhausted(long userId, int tier) {
        QuotaSnapshot snapshot = snapshot(userId, tier);
        return snapshot.dailyCardUsed() >= snapshot.dailyCardLimit();
    }

    private void checkQuotaCode(Long code) {
        if (code == null) {
            throw new IllegalStateException("Redis quota script returned null");
        }
        if (code == 1L) {
            throw quotaError(MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED);
        }
        if (code == 2L) {
            throw quotaError(MatchErrorCode.QUOTA_CARDS_EXCEEDED);
        }
    }

    private MatchBizException quotaError(int code) {
        return new MatchBizException(code, MatchErrorCode.getMessage(code));
    }

    private static String operationField(String operationKey) {
        return "super_hi_op:" + operationKey;
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static DefaultRedisScript<Long> script(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(text);
        script.setResultType(Long.class);
        return script;
    }

    public static String todayUtc() {
        return LocalDate.now(ZoneOffset.UTC).format(YYYYMMDD);
    }

    public static String quotaKey(long userId) {
        return "putao:match:quota:" + userId + ":" + todayUtc();
    }

    public record SuperHiCharge(int coinsUsed, boolean needCoinCharge) {
    }

    public record QuotaSnapshot(int dailyRightSwipeLimit, int dailyRightSwipeUsed,
                                int dailyCardLimit, int dailyCardUsed,
                                int dailySuperHiLimit, int dailySuperHiUsed,
                                int superHiCoinPrice, int tier) {
    }
}
