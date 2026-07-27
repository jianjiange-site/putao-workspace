package com.dating.match.recommend;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.UserTypeConst;
import com.dating.user.proto.BhCandidate;
import com.dating.user.proto.DhCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * D1 池内打分器(4.2.3).
 *
 * <p>公式:S(c) = base_score(c) + mutual_like_bonus(c) + new_bh_bonus(c)
 * base = 0.45 * preference_sim + 0.30 * normalize(beauty) + 0.15 * distance_decay + 0.10 * activity
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ranker {

    private final MatchProperties props;

    /** 高斯 PDF 截断阈值(|z| > 3 视为 0). */
    private static final double Z_CUTOFF = 3.0;

    /** 对 BH 池打分(完整公式). */
    public ScoredCandidate scoreBh(BhCandidate c, PreferenceProfile pref, Map<Long, Integer> mutualSwipedMe) {
        double[] w = props.getBhWeights();
        double prefSim = preferenceSimilarity(c.getAge(), c.getRace(), c.getBeautyScore(), pref);
        double beauty = normalize(c.getBeautyScore());
        double dist = distanceDecay(c.getDistanceKm());
        double activity = activityScore(c.getLastActiveAtMs());

        double base = w[0] * prefSim + w[1] * beauty + w[2] * dist + w[3] * activity;

        double mutualBonus = 0.0;
        if (mutualSwipedMe != null && mutualSwipedMe.containsKey(c.getUserId())) {
            mutualBonus = props.getMutualLikeBonus();
        }
        double newBhBonus = 0.0;
        if (isNewBh(c.getCreatedAtMs(), props.getNewBhWindowDays())) {
            newBhBonus = props.getNewBhBonus();
        }

        return new ScoredCandidate(c.getUserId(), UserTypeConst.BH, base + mutualBonus + newBhBonus);
    }

    /** 对 DH 池打分(DH 无距离 / 活跃度,两项固定 0.5). */
    public ScoredCandidate scoreDh(DhCandidate c, PreferenceProfile pref) {
        double[] w = props.getDhWeights();
        double prefSim = preferenceSimilarity(c.getAge(), c.getRace(), c.getBeautyScore(), pref);
        double beauty = normalize(c.getBeautyScore());
        double activity = 0.5; // DH 固定

        double base = w[0] * prefSim + w[1] * beauty + w[2] * 0.5 + w[3] * activity;

        // DH 无 mutual_like_bonus / new_bh_bonus
        return new ScoredCandidate(c.getUserId(), UserTypeConst.DH, base);
    }

    /** preference_similarity:age 高斯 * beauty 高斯 * race_dist. */
    private double preferenceSimilarity(int age, String race, int beauty, PreferenceProfile pref) {
        if (pref == null || pref.getAgeMean() == null || pref.getAgeStd() == null) {
            return 0.5;
        }
        double ageZ = Math.abs((age - pref.getAgeMean()) / Math.max(0.5, pref.getAgeStd()));
        double ageSim = ageZ >= Z_CUTOFF ? 0.0 : Math.exp(-0.5 * ageZ * ageZ);

        double beautyZ = pref.getBeautyMean() != null && pref.getBeautyStd() != null
                ? Math.abs((beauty - pref.getBeautyMean()) / Math.max(0.5, pref.getBeautyStd()))
                : 1.0;
        double beautySim = beautyZ >= Z_CUTOFF ? 0.0 : Math.exp(-0.5 * beautyZ * beautyZ);

        double raceSim = 0.5;
        if (race != null && pref.getRaceDist() != null && !pref.getRaceDist().isEmpty()) {
            Double r = pref.getRaceDist().get(race);
            raceSim = r != null ? r : 0.2;
        }

        return ageSim * beautySim * raceSim;
    }

    private double normalize(int beauty) {
        return Math.max(0.0, Math.min(1.0, beauty / 100.0));
    }

    private double distanceDecay(double distanceKm) {
        if (distanceKm < 0) {
            return 0.5; // DH 用
        }
        return Math.exp(-distanceKm / 50.0);
    }

    private double activityScore(long lastActiveAtMs) {
        if (lastActiveAtMs <= 0) {
            return 0.0;
        }
        Instant last = Instant.ofEpochMilli(lastActiveAtMs);
        double days = Duration.between(last, Instant.now()).toDays();
        return Math.exp(-days / 7.0);
    }

    private static boolean isNewBh(long createdAtMs, int windowDays) {
        if (createdAtMs <= 0) return false;
        Instant created = Instant.ofEpochMilli(createdAtMs);
        return Duration.between(created, Instant.now()).toDays() <= windowDays;
    }

    /** 取 top N(BH / DH 共用). */
    public <T> List<T> topN(List<T> items, java.util.function.Function<T, ScoredCandidate> scorer, int n) {
        return items.stream()
                .map(scorer)
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                .limit(n)
                .map(ScoredCandidate::candidateId)
                .map(id -> items.stream().filter(x -> {
                    ScoredCandidate sc = scorer.apply(x);
                    return sc.candidateId() == id;
                }).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** 排序后的 candidate id 列表. */
    public List<Long> topIds(List<ScoredCandidate> scored, int n) {
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                .limit(n)
                .map(ScoredCandidate::candidateId)
                .collect(Collectors.toList());
    }

    /** 内部评分包装. */
    public record ScoredCandidate(long candidateId, int userType, double score) {
    }
}