package com.dating.match.recommend;

import com.dating.match.config.MatchProperties;
import com.dating.user.proto.DhCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D0 / D1 池内排序 / 按比例 merge(4.1 / 4.2.5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedMerger {

    private final MatchProperties props;

    /**
     * D0 字典序硬排序:BH 池 4 级 / DH 池 3 级.
     *
     * @param isNewBhPredicate (uid) -> 该 BH 是否在 new_bh 窗口内
     */
    public List<DhCandidate> sortDhForD0(List<DhCandidate> dh,
                                         String userRace,
                                         int userAge) {
        String userR = userRace == null ? "" : userRace;
        return dh.stream()
                .sorted((a, b) -> {
                    // 1. same_race_as_user desc
                    boolean ar = userR.equals(a.getRace());
                    boolean br = userR.equals(b.getRace());
                    if (ar != br) return ar ? -1 : 1;
                    // 2. abs(age - user.age) asc
                    int da = Math.abs(a.getAge() - userAge);
                    int db = Math.abs(b.getAge() - userAge);
                    if (da != db) return Integer.compare(da, db);
                    // 3. beauty desc
                    return Integer.compare(b.getBeautyScore(), a.getBeautyScore());
                })
                .limit(props.getD1QueueSize())
                .collect(java.util.stream.Collectors.toList());
    }

    public List<com.dating.user.proto.BhCandidate> sortBhForD0(List<com.dating.user.proto.BhCandidate> bh,
                                                                java.util.function.Function<Long, Boolean> isNewBh,
                                                                String userRace,
                                                                int userAge,
                                                                int newBhWindowDays) {
        String userR = userRace == null ? "" : userRace;
        return bh.stream()
                .sorted((a, b) -> {
                    // 1. is_new_bh desc
                    boolean an = Boolean.TRUE.equals(isNewBh.apply(a.getUserId()));
                    boolean bn = Boolean.TRUE.equals(isNewBh.apply(b.getUserId()));
                    if (an != bn) return an ? -1 : 1;
                    // 2. same_race_as_user desc
                    boolean ar = userR.equals(a.getRace());
                    boolean br = userR.equals(b.getRace());
                    if (ar != br) return ar ? -1 : 1;
                    // 3. abs(age - user.age) asc
                    int da = Math.abs(a.getAge() - userAge);
                    int db = Math.abs(b.getAge() - userAge);
                    if (da != db) return Integer.compare(da, db);
                    // 4. beauty desc
                    return Integer.compare(b.getBeautyScore(), a.getBeautyScore());
                })
                .limit(props.getD1QueueSize())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 按比例 merge 两池.
     *
     * @param bhPool 已排好序的 BH 候选(可能 < target_bh)
     * @param dhPool 已排好序的 DH 候选(数量充足)
     * @param bhRatio 目标 BH 比例(0..1)
     * @return 合并后总长度 = queueSize;实际 BH 数 = min(target_bh, len(BH_pool))
     */
    public MergedFeed merge(List<?> bhPool, List<DhCandidate> dhPool, double bhRatio, int queueSize) {
        int targetBh = (int) Math.round(queueSize * bhRatio);
        int actualBh = Math.min(targetBh, bhPool.size());
        int shortBh = targetBh - actualBh;
        int actualDh = (queueSize - targetBh) + shortBh;
        actualDh = Math.min(actualDh, dhPool.size());

        List<MergedEntry> merged = new ArrayList<>(queueSize);
        List<DhCandidate> usedDh = new ArrayList<>(actualDh);

        // 交错插入:每 (1/bhRatio) 张 DH 间塞 1 张 BH
        int bhCount = 0;
        int dhCount = 0;
        // 简化:先按 1:1.5 (BH:DH) 比例交错,直到一边用完
        int step = bhRatio <= 0 ? 1 : (int) Math.max(1, Math.round(1.0 / bhRatio));
        int cycle = step + 1;

        while (merged.size() < queueSize && (bhCount < actualBh || dhCount < actualDh)) {
            // 优先 BH(实际有就用)
            for (int i = 0; i < step && bhCount < actualBh && merged.size() < queueSize; i++) {
                Object bh = bhPool.get(bhCount);
                merged.add(MergedEntry.fromBh(bh));
                bhCount++;
            }
            if (dhCount < actualDh && merged.size() < queueSize) {
                DhCandidate dh = dhPool.get(dhCount);
                merged.add(MergedEntry.fromDh(dh));
                usedDh.add(dh);
                dhCount++;
            }
        }

        return new MergedFeed(merged, usedDh, actualBh, actualDh);
    }

    /** D1 个性化比例(L1 + L2 偏移). */
    public double computeBhRatioD1(PreferenceProfile pref) {
        double base = props.getD1BhRatio();
        if (!props.isD1PreferenceEnabled() || pref == null || pref.getDhBhRatio() == null
                || pref.getSampleCount() < props.getMinSamples()) {
            return base;
        }
        double offset = (0.5 - pref.getDhBhRatio()) * props.getD1PreferenceOffset() * 2;
        offset = Math.max(-props.getD1PreferenceOffset(), Math.min(props.getD1PreferenceOffset(), offset));
        return Math.max(0.0, Math.min(1.0, base + offset));
    }

    public double computeBhRatioD0() {
        return props.getColdStartBhRatio();
    }

    /** 合并后的混合 feed 条目. */
    public interface MergedEntry {
        long candidateId();
        int userType();

        static MergedEntry fromBh(Object bh) {
            if (bh instanceof com.dating.user.proto.BhCandidate b) {
                return new Default(b.getUserId(), com.dating.match.constant.UserTypeConst.BH);
            }
            throw new IllegalArgumentException("Unsupported BH type: " + bh.getClass());
        }

        static MergedEntry fromDh(DhCandidate dh) {
            return new Default(dh.getUserId(), com.dating.match.constant.UserTypeConst.DH);
        }
    }

    record Default(long candidateId, int userType) implements MergedEntry {
    }

    public record MergedFeed(List<MergedEntry> entries, List<DhCandidate> usedDh, int actualBh, int actualDh) {
    }
}