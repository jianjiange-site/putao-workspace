package com.dating.match.recommend;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.manager.UserSwipeHistoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好建模(4.2.1).
 *
 * <p>从最近 30 天 {@code user_swipe_history} 聚合右划画像分布.
 * 样本 < 10 时返回 {@code isValid() = false},由调用方回退 D0.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreferenceBuilder {

    /** 30 天窗 */
    private static final Duration RECENT_WINDOW = Duration.ofDays(30);

    private final UserSwipeHistoryManager swipeHistoryManager;
    private final MatchProperties props;

    /**
     * 聚合用户偏好画像.
     *
     * @param userId 用户 ID
     * @return 偏好画像;样本不足时返回无效实例
     */
    public PreferenceProfile build(long userId) {
        Instant since = Instant.now().minus(RECENT_WINDOW);
        List<UserSwipeHistoryEntity> swipes = swipeHistoryManager.listRecentSwipes(userId, since);
        // 仅 RIGHT
        List<UserSwipeHistoryEntity> rightSwipes = swipes.stream()
                .filter(s -> s.getDirection() != null
                        && s.getDirection() == SwipeDirectionConst.RIGHT)
                .toList();

        PreferenceProfile profile = PreferenceProfile.builder().build();
        profile.setSampleCount(rightSwipes.size());

        if (rightSwipes.size() < props.getMinSamples()) {
            return profile;
        }

        // age / beauty 聚合
        double ageSum = 0, ageSqSum = 0;
        double beautySum = 0, beautySqSum = 0;
        int ageCount = 0, beautyCount = 0;
        Map<String, Integer> raceCount = new HashMap<>();
        int dhCount = 0, bhCount = 0;

        for (UserSwipeHistoryEntity s : rightSwipes) {
            // target 端的具体属性(age/beauty/race)需要 join  user-service;
            // 此处 swipe_history 表只存 user_id / target_user_id,所以我们用目标 ID 反查 user-service.
            // 这里出于简化,只统计可从 swipe_history 拿到的 direction,
            // 实际生产中要么 (a) 把 target age/beauty 冗余进 swipe_history,
            // 要么 (b) 在 preferenceBuilder 里调 user-service.batchGetProfile.
            // 当前实现选择 (b):
            // 因为 gRPC 调用比较重,这里采用"在 D1Generator 里集中 batchGetProfile" 的策略.
            ageCount++;
            beautyCount++;
            ageSum += s.getTargetUserId();   // 占位,真实值在 D1Generator 里覆盖
            beautySum += s.getTargetUserId(); // 占位
        }

        // 简化版:仅返回 sampleCount + 方向分布,d1Generator 注入 target profile
        profile.setDhBhRatio((double) dhCount / Math.max(1, rightSwipes.size()));
        // 真实 age/beauty 由 D1Generator 在 batchGetProfile 后回填

        return profile;
    }

    /**
     * 用 D1Generator 一次性拉取的 target profile 回填偏好统计.
     *
     * <p>右划 target 的 user_type 已经在 swipe_history.target_user_type 字段中.
     */
    public PreferenceProfile build(long userId, Map<Long, TargetStats> stats) {
        Instant since = Instant.now().minus(RECENT_WINDOW);
        List<UserSwipeHistoryEntity> swipes = swipeHistoryManager.listRecentSwipes(userId, since);
        List<UserSwipeHistoryEntity> rightSwipes = swipes.stream()
                .filter(s -> s.getDirection() != null
                        && s.getDirection() == SwipeDirectionConst.RIGHT)
                .toList();

        if (rightSwipes.size() < props.getMinSamples()) {
            return PreferenceProfile.builder().sampleCount(rightSwipes.size()).build();
        }

        double ageSum = 0, ageSqSum = 0;
        double beautySum = 0, beautySqSum = 0;
        int ageCount = 0, beautyCount = 0;
        Map<String, Integer> raceCount = new HashMap<>();
        int dhCount = 0, bhCount = 0;

        for (UserSwipeHistoryEntity s : rightSwipes) {
            TargetStats stat = stats.get(s.getTargetUserId());
            if (stat == null) continue;

            if (stat.age != null) {
                ageSum += stat.age;
                ageSqSum += (double) stat.age * stat.age;
                ageCount++;
            }
            if (stat.beautyScore != null) {
                beautySum += stat.beautyScore;
                beautySqSum += (double) stat.beautyScore * stat.beautyScore;
                beautyCount++;
            }
            if (stat.race != null) {
                raceCount.merge(stat.race, 1, Integer::sum);
            }
            if (s.getTargetUserType() != null) {
                if (s.getTargetUserType() == UserTypeConst.DH) {
                    dhCount++;
                } else {
                    bhCount++;
                }
            }
        }

        PreferenceProfile profile = PreferenceProfile.builder()
                .sampleCount(rightSwipes.size())
                .build();

        if (ageCount > 0) {
            double mean = ageSum / ageCount;
            double variance = Math.max(0, ageSqSum / ageCount - mean * mean);
            profile.setAgeMean(mean);
            profile.setAgeStd(Math.sqrt(variance));
        }
        if (beautyCount > 0) {
            double mean = beautySum / beautyCount;
            double variance = Math.max(0, beautySqSum / beautyCount - mean * mean);
            profile.setBeautyMean(mean);
            profile.setBeautyStd(Math.sqrt(variance));
        }

        Map<String, Double> raceDist = new HashMap<>();
        int totalRace = raceCount.values().stream().mapToInt(Integer::intValue).sum();
        if (totalRace > 0) {
            for (Map.Entry<String, Integer> e : raceCount.entrySet()) {
                raceDist.put(e.getKey(), (double) e.getValue() / totalRace);
            }
        }
        profile.setRaceDist(raceDist);

        int total = dhCount + bhCount;
        profile.setDhBhRatio(total > 0 ? (double) dhCount / total : null);

        return profile;
    }

    /** D1Generator 注入的 target 统计信息 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TargetStats {
        private Integer age;
        private Integer beautyScore;
        private String race;
    }
}
