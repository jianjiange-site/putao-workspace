package com.dating.match.recommend;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.user.proto.BhCandidate;
import com.dating.user.proto.DhCandidate;
import com.dating.user.proto.Gender;
import com.dating.user.proto.ListDhCandidatesRequest;
import com.dating.user.proto.NearbyUsersRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选人召回器(4.1 / 4.2.2).
 *
 * <p>两池独立召回:
 * <ul>
 *   <li>DH 池:支持渐进扩范围(D0 用);D1 直接一次拉满</li>
 *   <li>BH 池:严格条件一次拉;不够就不够</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateRecaller {

    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final UserSwipeHistoryManager swipeHistoryManager;
    private final MatchProperties props;

    /** 每池目标条数. */
    private static final int POOL_TARGET = 240;

    /**
     * D0 DH 池召回:渐进扩范围 L0→L3,去重累积到目标.
     *
     * @param userId      召回发起方用户
     * @param targetGender 1=MALE 2=FEMALE(opposite gender)
     * @param excludeUserIds 已 swipe / 已 match 等
     * @param userAge 中心点年龄(供渐进层级用)
     * @param userBeauty 中心点颜值
     * @param userRace 用户人种(L0 优先同人种)
     */
    public List<DhCandidate> recallDhPoolD0(long userId,
                                            int targetGender,
                                            List<Long> excludeUserIds,
                                            int userAge,
                                            int userBeauty,
                                            String userRace) {
        // excludeUserIds:已 swipe target 列表（用userId去查询user_swipe表，获取已swipe的userId列表）
        Set<Long> seen = new LinkedHashSet<>(excludeUserIds == null ? List.of() : excludeUserIds);
        // accumulated:已召回的候选人列表
        List<DhCandidate> accumulated = new ArrayList<>();
        // levels:渐进层级列表
        List<String> levels = props.getColdStartDhLevels();
        // ageWindow:年龄窗口，初始化
        int[] ageWindow = {props.getColdStartBhAgeWindow()};
        // beautyWindow:颜值窗口，初始化
        int[] beautyWindow = {props.getColdStartBhBeautyWindow()};
        // races:人种列表，初始化
        String[] races = {userRace};

        for (String level : levels) {
            // 如果已召回的候选人数量 >= 目标数量，则跳出循环
            if (accumulated.size() >= POOL_TARGET) {
                break;
            }
            //根据level赋值ageWindow, beautyWindow, races
            applyDhLevel(level, ageWindow, beautyWindow, races);
            // need:需要召回的候选人数量 = 目标数量 - 已召回的候选人数量
            int need = POOL_TARGET - accumulated.size();

            // 构造ListDhCandidatesRequest请求
            ListDhCandidatesRequest req = ListDhCandidatesRequest.newBuilder()
                    .setTargetGender(targetGender)
                    .setAgeMin(userAge - ageWindow[0])
                    .setAgeMax(userAge + ageWindow[0])
                    .setBeautyMin(Math.max(0, userBeauty - beautyWindow[0]))
                    .setBeautyMax(userBeauty + beautyWindow[0])
                    .addAllRaces(races[0] == null ? List.of() : List.of(races[0]))
                    .addAllExcludeUserIds(new ArrayList<>(seen))
                    .setLimit(Math.min(need + 50, 500)) // 多拉一些防 RPC 配额 / 服务端截断
                    .build();

            // 调用userServiceClient.listDhCandidates(req)，获取候选人列表
            List<DhCandidate> batch = userServiceClient.listDhCandidates(req);
            // 遍历候选人列表
            for (DhCandidate c : batch) {
                // 如果候选人列表中不存在该用户，则添加到已召回的候选人列表中
                if (seen.add(c.getUserId())) {
                    accumulated.add(c);
                    if (accumulated.size() >= POOL_TARGET) break;
                }
            }
            log.debug("D0 DH recall level={} got={} accum={}", level, batch.size(), accumulated.size());
        }
        return accumulated;
    }

    /**
     * 应用 DH 渐进层级参数.
     */
    private void applyDhLevel(String level, int[] ageWindow, int[] beautyWindow, String[] races) {
        switch (level) {
            case "L0" -> {
                ageWindow[0] = 5;
                beautyWindow[0] = 15;
                races[0] = races[0]; // 保留同人种
            }
            case "L1" -> {
                ageWindow[0] = 5;
                beautyWindow[0] = 15;
                races[0] = null; // 放开人种
            }
            case "L2" -> {
                ageWindow[0] = 10;
                beautyWindow[0] = 25;
                races[0] = null;
            }
            case "L3" -> {
                ageWindow[0] = 60;
                beautyWindow[0] = 60;
                races[0] = null;
            }
            default -> {
                ageWindow[0] = 60;
                beautyWindow[0] = 60;
                races[0] = null;
            }
        }
    }

    /**
     * D1 DH 池召回:单层(用户的偏好画像驱动).
     *
     * @param pref 用户偏好画像
     */
    public List<DhCandidate> recallDhPoolD1(long userId,
                                            int targetGender,
                                            List<Long> excludeUserIds,
                                            PreferenceProfile pref) {
        int ageMin = pref.getAgeMean() != null
                ? Math.max(18, (int) Math.round(pref.getAgeMean() - 2 * Math.max(1.0, pref.getAgeStd())))
                : 18;
        int ageMax = pref.getAgeMean() != null
                ? Math.min(70, (int) Math.round(pref.getAgeMean() + 2 * Math.max(1.0, pref.getAgeStd())))
                : 70;
        int beautyMin = pref.getBeautyMean() != null
                ? Math.max(0, (int) Math.round(pref.getBeautyMean() - 20))
                : 0;
        int beautyMax = pref.getBeautyMean() != null
                ? Math.min(100, (int) Math.round(pref.getBeautyMean() + 20))
                : 100;

        ListDhCandidatesRequest req = ListDhCandidatesRequest.newBuilder()
                .setTargetGender(targetGender)
                .setAgeMin(ageMin)
                .setAgeMax(ageMax)
                .setBeautyMin(beautyMin)
                .setBeautyMax(beautyMax)
                .addAllExcludeUserIds(excludeUserIds == null ? List.of() : excludeUserIds)
                .setLimit(POOL_TARGET)
                .build();

        List<DhCandidate> batch = userServiceClient.listDhCandidates(req);
        log.debug("D1 DH recall got={} target={}", batch.size(), POOL_TARGET);
        return batch;
    }

    /**
     * D0 / D1 BH 池召回:严格条件一次,不够就不够(merge 阶段 DH 补齐).
     */
    public List<BhCandidate> recallBhPool(long userId,
                                          double radiusKm,
                                          int ageMin,
                                          int ageMax,
                                          int beautyMin,
                                          int beautyMax,
                                          List<String> races,
                                          int lastActiveWithinDays,
                                          List<Long> excludeUserIds) {
        NearbyUsersRequest req = NearbyUsersRequest.newBuilder()
                .setUserId(userId)
                .setRadiusKm(radiusKm)
                .setAgeMin(ageMin)
                .setAgeMax(ageMax)
                .setBeautyMin(beautyMin)
                .setBeautyMax(beautyMax)
                .addAllRaces(races)
                .setLastActiveWithinDays(lastActiveWithinDays)
                .addAllExcludeUserIds(excludeUserIds == null ? List.of() : excludeUserIds)
                .setLimit(POOL_TARGET)
                .build();
        List<BhCandidate> batch = userServiceClient.nearbyUsers(req);
        log.debug("BH recall radius={}km got={}", radiusKm, batch.size());
        return batch;
    }

    /**
     * 反查用户对哪些 target 已 swipe(用于 BH 召回时排除).
     */
    public List<Long> excludeUserIds(long userId) {
        Set<Long> set = new HashSet<>(swipeHistoryManager.listSwipedTargetIds(userId));
        return new ArrayList<>(set);
    }

    /**
     * 性别反推(异性恋假设).
     */
    public static int oppositeGender(int gender) {
        if (gender == Gender.GENDER_MALE.getNumber()) {
            return Gender.GENDER_FEMALE.getNumber();
        }
        if (gender == Gender.GENDER_FEMALE.getNumber()) {
            return Gender.GENDER_MALE.getNumber();
        }
        return Gender.GENDER_UNKNOWN.getNumber();
    }

    public static boolean isDh(int userType) {
        return userType == UserTypeConst.DH;
    }

    public static boolean isBh(int userType) {
        return userType == UserTypeConst.BH;
    }
}