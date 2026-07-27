package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.MatchRedisKey;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.recommend.CandidateRecaller;
import com.dating.match.recommend.FeedMerger;
import com.dating.match.recommend.FeedMerger.MergedEntry;
import com.dating.match.recommend.FeedMerger.MergedFeed;
import com.dating.match.recommend.PreferenceBuilder;
import com.dating.match.recommend.PreferenceProfile;
import com.dating.match.recommend.Ranker;
import com.dating.user.proto.BhCandidate;
import com.dating.user.proto.DhCandidate;
import com.dating.user.proto.Gender;
import com.dating.user.proto.UserProfileProto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D1 日更队列生成器(4.2).
 *
 * <p>两池独立召回 → 池内打分 → 按比例 merge → DEL+RPUSH 覆盖 Redis LIST.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class D1Generator {

    private final CandidateRecaller recaller;
    private final Ranker ranker;
    private final FeedMerger merger;
    private final PreferenceBuilder preferenceBuilder;
    private final UserSwipeHistoryManager swipeHistoryManager;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final MatchProperties props;

    /**
     * 为单个用户生成 D1 队列并覆盖 Redis LIST.
     *
     * @param userId 用户 ID
     * @return 写入条数;前置条件不满足返回 0
     */
    public int generateForUser(long userId) {
        // 1. 前置条件:用户昨天有过 swipe
        Instant now = Instant.now();
        // 昨天开始时间
        Instant yesterdayStart = now.minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        // 昨天结束时间
        Instant yesterdayEnd = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        // 如果用户昨天没有swipe，则跳过
        if (!swipeHistoryManager.existsSwipeInRange(userId, yesterdayStart, yesterdayEnd)) {
            log.debug("D1 skip: no swipe yesterday userId={}", userId);
            return 0;
        }

        // 2. 用户画像
        List<UserProfileProto> profiles = userServiceClient.batchGetProfile(List.of(userId));
        // 如果用户画像不存在，则跳过
        if (profiles.isEmpty()) {
            log.warn("D1 skip: profile missing userId={}", userId);
            return 0;
        }
        UserProfileProto user = profiles.get(0);
        // 获取用户性别
        int targetGender = CandidateRecaller.oppositeGender(user.getGender().getNumber());
        // 获取用户年龄
        int userAge = user.getAge() > 0 ? user.getAge() : 25;

        // 3. 偏好建模
        // 构建偏好
        PreferenceProfile pref = preferenceBuilder.build(userId);
        // 如果偏好不合法，则回退用户自身画像
        if (!pref.isValid()) {
            log.debug("D1 fallback to D0 prior for userId={}", userId);
            // 样本不足,回退用户自身画像
            pref = buildFallbackPrior(user);
        }

        // 4. exclude_user_ids = 已 swipe 全部 + 已 match(可选,简化只走 swipe)
        List<Long> exclude = new ArrayList<>(swipeHistoryManager.listSwipedTargetIds(userId));
        exclude.add(userId); // 排除自身

        // 5. DH 池
        List<DhCandidate> dhRaw = recaller.recallDhPoolD1(userId, targetGender, exclude, pref);

        // 6. BH 池
        List<BhCandidate> bhRaw = recaller.recallBhPool(
                userId,
                props.getD1BhRadiusKm(),
                Math.max(18, userAge - 10),
                userAge + 10,
                0, 100,
                List.of(),
                props.getD1BhActiveDays(),
                exclude);

        // 7. 池内打分
        // 反查 BH 互划信号:target_id -> boolean(曾对 user 做过 RIGHT/SUPER_HI)
        Map<Long, Integer> mutual = buildMutualMap(userId, bhRaw);

        // 打分
        List<Ranker.ScoredCandidate> dhScored = new ArrayList<>();
        for (DhCandidate c : dhRaw) {
            // 调用ranker.scoreDh(c, pref)，获取打分结果
            dhScored.add(ranker.scoreDh(c, pref));
        }
        List<Ranker.ScoredCandidate> bhScored = new ArrayList<>();
        for (BhCandidate c : bhRaw) {
            // 调用ranker.scoreBh(c, pref, mutual)，获取打分结果
            bhScored.add(ranker.scoreBh(c, pref, mutual));
        }

        // 8. 取 top 240，仅含userId
        List<Long> dhTopIds = ranker.topIds(dhScored, props.getD1QueueSize());
        List<Long> bhTopIds = ranker.topIds(bhScored, props.getD1QueueSize());

        // 9. 转回 Candidate 对象(按 id 反查)
        List<DhCandidate> dhFinal = new ArrayList<>();
        for (Long id : dhTopIds) {
            for (DhCandidate c : dhRaw) {
                if (c.getUserId() == id) {
                    dhFinal.add(c);
                    break;
                }
            }
        }
        List<BhCandidate> bhFinal = new ArrayList<>();
        for (Long id : bhTopIds) {
            for (BhCandidate c : bhRaw) {
                if (c.getUserId() == id) {
                    bhFinal.add(c);
                    break;
                }
            }
        }

        // 10. merge
        MergedFeed merged = merger.merge(bhFinal, dhFinal, merger.computeBhRatioD1(pref), props.getD1QueueSize());

        // 11. DEL + RPUSH 覆盖
        String key = MatchRedisKey.feed(userId);
        stringRedisTemplate.delete(key);
        return pushToRedis(userId, merged.entries());
    }

    /**
     * 写入 Redis LIST(DEL+RPUSH).
     */
    public int pushToRedis(long userId, List<MergedEntry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }
        String key = MatchRedisKey.feed(userId);
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
        return elements.size();
    }

    /**
     * 收集 BH 候选中哪些曾对 user 做过 RIGHT/SUPER_HI(用于 mutual_like_bonus).
     */
    private Map<Long, Integer> buildMutualMap(long userId, List<BhCandidate> bhRaw) {
        Map<Long, Integer> map = new java.util.HashMap<>();
        for (BhCandidate c : bhRaw) {
            // 简化:仅在已有 swipe_history 中查;这里由 swipeHistoryManager 提供
            // 实际生产建议加一个 mapper.findByTargetsAndDirection(targetIds, dir)
            // 这里走 N 次 mapper(候选最多 240,单实例 cron 一次扫描用户列表数大,需要批量化)
            // 简化:此处不查,所有 BH 都不触发 mutual;正式实现时应批查
        }
        return map;
    }

    private PreferenceProfile buildFallbackPrior(UserProfileProto user) {
        PreferenceProfile pref = PreferenceProfile.builder().build();
        pref.setAgeMean((double) user.getAge());
        pref.setAgeStd(8.0);
        pref.setBeautyMean(60.0);
        pref.setBeautyStd(15.0);
        pref.setSampleCount(0);
        return pref;
    }
}