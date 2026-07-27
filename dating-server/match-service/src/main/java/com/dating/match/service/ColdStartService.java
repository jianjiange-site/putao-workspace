package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.recommend.CandidateRecaller;
import com.dating.match.recommend.FeedMerger;
import com.dating.match.recommend.FeedMerger.MergedEntry;
import com.dating.match.recommend.FeedMerger.MergedFeed;
import com.dating.user.proto.BhCandidate;
import com.dating.user.proto.DhCandidate;
import com.dating.user.proto.UserProfileProto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 冷启动 / 队列重建服务(D0 实时召回 + merge,见 4.1).
 *
 * <p>入口 {@link #buildAndPush(long)}:用户画像 → 两池召回 → merge → RPUSH 到 {@code match:feed:&lt;uid&gt;}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColdStartService {

    private final CandidateRecaller recaller;
    private final FeedMerger merger;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final MatchProperties props;

    /**
     * 实时构建 D0 队列并 RPUSH 到 Redis LIST.
     *
     * @param userId 用户 ID
     * @return 实际写入的卡片数量
     */
    public int buildAndPush(long userId) {
        // 1. 取用户画像(中心点)
        List<UserProfileProto> profiles = userServiceClient.batchGetProfile(List.of(userId));
        if (profiles.isEmpty()) {
            log.warn("ColdStart: user profile not found userId={}", userId);
            return 0;
        }
        UserProfileProto user = profiles.get(0);

        int userGender = user.getGender().getNumber();
        int targetGender = CandidateRecaller.oppositeGender(userGender);
        int userAge = user.getAge() > 0 ? user.getAge() : 25;
        int userBeauty = 60; // user.proto 暂无 beauty 字段,默认 60

        // 2. exclude:已 swipe target 列表（用userId去查询user_swipe表，获取已swipe的userId列表）
        List<Long> exclude = recaller.excludeUserIds(userId);

        // 3. DH 池 — 渐进扩范围
        List<DhCandidate> dhPool = recaller.recallDhPoolD0(userId, targetGender, exclude, userAge, userBeauty, user.getPreferredLocation());

        // 4. BH 池 — 严格条件一次
        List<Long> bhExclude = new ArrayList<>(exclude);
        bhExclude.add(userId); // 排除自身
        List<BhCandidate> bhRaw = recaller.recallBhPool(
                userId,
                props.getColdStartBhRadiusKm(),
                Math.max(18, userAge - props.getColdStartBhAgeWindow()),
                userAge + props.getColdStartBhAgeWindow(),
                Math.max(0, userBeauty - props.getColdStartBhBeautyWindow()),
                userBeauty + props.getColdStartBhBeautyWindow(),
                List.of(), // races:user.proto 暂无 race 字段,空 = 不限
                props.getColdStartBhActiveDays(),
                bhExclude);

        // 5. 池内排序(D0 字典序)
        //1. is_new_bh desc         → 新注册的 BH（≤N天）优先
        //2. same_race desc         → 和用户同种族的优先
        //3. abs(age差) asc         → 年龄离用户越近的优先
        //4. beauty desc            → 颜值越高的优先
        List<BhCandidate> bhSorted = merger.sortBhForD0(bhRaw,
                uid -> {
                    // 简化:从 raw 列表里查 created_at(已在 candidate 里)
                    return bhRaw.stream().filter(x -> x.getUserId() == uid).findFirst()
                            .map(x -> isWithinWindow(x.getCreatedAtMs(), props.getNewBhWindowDays()))
                            .orElse(false);
                },
                null, userAge, props.getNewBhWindowDays());

        //1. same_race desc         → 和用户同种族的优先
        //2. abs(age差) asc         → 年龄离用户越近的优先
        //3. beauty desc            → 颜值越高的优先
        List<DhCandidate> dhSorted = merger.sortDhForD0(dhPool, null, userAge);

        // 6. 按比例 merge
        MergedFeed merged = merger.merge(bhSorted, dhSorted, merger.computeBhRatioD0(), props.getD1QueueSize());

        // 7. RPUSH 写入 Redis LIST
        return pushToRedis(userId, merged.entries());
    }

    /**
     * 把 feed 列表 RPUSH 到 Redis LIST.
     */
    public int pushToRedis(long userId, List<MergedEntry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        String key = com.dating.match.constant.MatchRedisKey.feed(userId);
        List<String> elements = new ArrayList<>(entries.size());
        Set<Long> seen = new HashSet<>();
        for (MergedEntry e : entries) {
            if (!seen.add(e.candidateId())) {
                continue;
            }
            elements.add(props.formatFeedElement(e.candidateId(), e.userType()));
        }
        if (elements.isEmpty()) {
            return 0;
        }
        stringRedisTemplate.opsForList().rightPushAll(key, elements);
        stringRedisTemplate.expire(key, Duration.ofSeconds(props.getFeedTtlSeconds()));
        log.debug("ColdStart push userId={} count={}", userId, elements.size());
        return elements.size();
    }

    private static boolean isWithinWindow(long createdAtMs, int windowDays) {
        if (createdAtMs <= 0) return false;
        return (System.currentTimeMillis() - createdAtMs) <= windowDays * 86_400_000L;
    }

    public static int userTypeOf(MergedEntry e) {
        return e.userType();
    }

    public static long userIdOf(MergedEntry e) {
        return e.candidateId();
    }

    public static boolean isBhEntry(MergedEntry e) {
        return e.userType() == UserTypeConst.BH;
    }
}