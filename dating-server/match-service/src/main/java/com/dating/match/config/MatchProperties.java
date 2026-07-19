package com.dating.match.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Match 配置项(读 Nacos YAML).
 *
 * <p>本类仅做配置注入;具体 Nacos key 集中在各 service 内部读取.
 */
@Configuration
public class MatchProperties {

    // ==================== D0 冷启动(4.1) ====================

    /** BH 池比例,默认 0.20 */
    @Value("${match.cold_start.bh_ratio:0.20}")
    private double coldStartBhRatio;

    /** BH 池距离阈值 km,默认 100 */
    @Value("${match.cold_start.bh.radius_km:100}")
    private double coldStartBhRadiusKm;

    /** BH 池年龄窗口 ±,默认 5 */
    @Value("${match.cold_start.bh.age_window:5}")
    private int coldStartBhAgeWindow;

    /** BH 池颜值窗口 ±,默认 15 */
    @Value("${match.cold_start.bh.beauty_window:15}")
    private int coldStartBhBeautyWindow;

    /** BH 池最近活跃天数,默认 7 */
    @Value("${match.cold_start.bh.last_active_within_days:7}")
    private int coldStartBhActiveDays;

    /** DH 池渐进层级 */
    @Value("${match.cold_start.levels.dh:L0:L1:L2:L3}")
    private String coldStartDhLevels;

    // ==================== D1 ====================

    /** D1 bh_ratio,默认 0.40 */
    @Value("${match.d1.bh_ratio:0.40}")
    private double d1BhRatio;

    /** D1 BH 池 radius_km,默认 200 */
    @Value("${match.d1.bh.radius_km:200}")
    private double d1BhRadiusKm;

    /** D1 BH 池 last_active_within_days,默认 7 */
    @Value("${match.d1.bh.last_active_within_days:7}")
    private int d1BhActiveDays;

    /** D1 个性化偏移开关,默认 true */
    @Value("${match.d1.preference_enabled:true}")
    private boolean d1PreferenceEnabled;

    /** D1 个性化偏移上限,默认 ±0.20 */
    @Value("${match.d1.preference_offset:0.20}")
    private double d1PreferenceOffset;

    /** D1 队列容量,固定 240 */
    @Value("${match.d1.queue_size:240}")
    private int d1QueueSize;

    // ==================== 打分权重 ====================

    /** BH 池权重 */
    @Value("${match.score.bh_weights:0.45,0.30,0.15,0.10}")
    private String bhWeightsRaw;

    /** DH 池权重 */
    @Value("${match.score.dh_weights:0.45,0.30,0.15,0.10}")
    private String dhWeightsRaw;

    /** mutual_like_bonus 默认 +0.20 */
    @Value("${match.score.mutual_like_bonus:0.20}")
    private double mutualLikeBonus;

    /** new_bh_bonus 默认 +0.20 */
    @Value("${match.score.new_bh_bonus:0.20}")
    private double newBhBonus;

    /** new_bh_window_days 默认 3 */
    @Value("${match.score.new_bh_window_days:3}")
    private int newBhWindowDays;

    /** 偏好建模最少样本,默认 10 */
    @Value("${match.score.min_samples:10}")
    private int minSamples;

    // ==================== DH 计划 ====================

    /** ONLINE 单次生成数,默认 [5,10] */
    @Value("${match.dh_plan.online_count_min:5}")
    private int onlineCountMin;
    @Value("${match.dh_plan.online_count_max:10}")
    private int onlineCountMax;

    /** OFFLINE 单次生成数,默认 [3,6] */
    @Value("${match.dh_plan.offline_count_min:3}")
    private int offlineCountMin;
    @Value("${match.dh_plan.offline_count_max:6}")
    private int offlineCountMax;

    /** ONLINE cooldown 默认 7200s (2h) */
    @Value("${match.dh_plan.online_cooldown_seconds:7200}")
    private long onlineCooldownSeconds;

    /** OFFLINE threshold 默认 1200s (20min) */
    @Value("${match.dh_plan.offline_threshold_seconds:1200}")
    private long offlineThresholdSeconds;

    /** OFFLINE lookback 默认 10800s (3h) */
    @Value("${match.dh_plan.offline_lookback_seconds:10800}")
    private long offlineLookbackSeconds;

    /** ONLINE execute window 默认 30min */
    @Value("${match.dh_plan.online_execute_window_min:30}")
    private int onlineExecuteWindowMin;

    /** OFFLINE execute window 默认 30min */
    @Value("${match.dh_plan.offline_execute_window_min:30}")
    private int offlineExecuteWindowMin;

    /** VISIT 比例,默认 0.60 */
    @Value("${match.dh_plan.visit_ratio:0.6}")
    private double visitRatio;

    /** DH 24h like 上限,默认 15 */
    @Value("${match.dh_plan.daily_dh_like_cap:15}")
    private int dailyDhLikeCap;

    /** DH 24h visit 上限,默认 25 */
    @Value("${match.dh_plan.daily_dh_visit_cap:25}")
    private int dailyDhVisitCap;

    /** like_content 模板 (JSON) */
    @Value("${match.dh_plan.like_content_templates:[]}")
    private String likeContentTemplates;

    // ==================== Redis TTL ====================

    /** feed LIST TTL,默认 7 天 */
    @Value("${match.redis.feed_ttl_seconds:604800}")
    private long feedTtlSeconds;

    /** quota HASH TTL,默认 36h */
    @Value("${match.redis.quota_ttl_seconds:129600}")
    private long quotaTtlSeconds;

    /** pref HASH TTL,默认 24h */
    @Value("${match.redis.pref_ttl_seconds:86400}")
    private long prefTtlSeconds;

    // ==================== 调度 ====================

    /** outbox 每次扫描条数,默认 100 */
    @Value("${match.outbox.scan_limit:100}")
    private int outboxScanLimit;

    /** outbox 最大 attempts,默认 5 */
    @Value("${match.outbox.max_attempts:5}")
    private int outboxMaxAttempts;

    /** dh_task executor 每次扫描条数,默认 1000 */
    @Value("${match.dh_task.scan_limit:1000}")
    private int dhTaskScanLimit;

    // ==================== 通用 ====================

    /** 移动端默认 count */
    @Value("${match.feed.default_count:5}")
    private int defaultCount;

    /** 移动端 max count */
    @Value("${match.feed.max_count:20}")
    private int maxCount;

    /** 列表分页 max size */
    @Value("${match.list.max_page_size:50}")
    private int maxPageSize;

    public double getColdStartBhRatio() { return coldStartBhRatio; }
    public double getD1BhRatio() { return d1BhRatio; }
    public int getD1QueueSize() { return d1QueueSize; }
    public boolean isD1PreferenceEnabled() { return d1PreferenceEnabled; }
    public double getD1PreferenceOffset() { return d1PreferenceOffset; }
    public double getMutualLikeBonus() { return mutualLikeBonus; }
    public double getNewBhBonus() { return newBhBonus; }
    public int getNewBhWindowDays() { return newBhWindowDays; }
    public int getMinSamples() { return minSamples; }

    public double getColdStartBhRadiusKm() { return coldStartBhRadiusKm; }
    public int getColdStartBhAgeWindow() { return coldStartBhAgeWindow; }
    public int getColdStartBhBeautyWindow() { return coldStartBhBeautyWindow; }
    public int getColdStartBhActiveDays() { return coldStartBhActiveDays; }
    public double getD1BhRadiusKm() { return d1BhRadiusKm; }
    public int getD1BhActiveDays() { return d1BhActiveDays; }

    public List<String> getColdStartDhLevels() {
        if (coldStartDhLevels == null || coldStartDhLevels.isBlank()) {
            return List.of("L0", "L1", "L2", "L3");
        }
        return Arrays.asList(coldStartDhLevels.split(":"));
    }

    public double[] getBhWeights() {
        return parseWeights(bhWeightsRaw);
    }

    public double[] getDhWeights() {
        return parseWeights(dhWeightsRaw);
    }

    private double[] parseWeights(String raw) {
        if (raw == null || raw.isBlank()) {
            return new double[]{0.45, 0.30, 0.15, 0.10};
        }
        String[] parts = raw.split(",");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
        }
        return result;
    }

    public int getOnlineCountMin() { return onlineCountMin; }
    public int getOnlineCountMax() { return onlineCountMax; }
    public int getOfflineCountMin() { return offlineCountMin; }
    public int getOfflineCountMax() { return offlineCountMax; }
    public long getOnlineCooldownSeconds() { return onlineCooldownSeconds; }
    public long getOfflineThresholdSeconds() { return offlineThresholdSeconds; }
    public long getOfflineLookbackSeconds() { return offlineLookbackSeconds; }
    public int getOnlineExecuteWindowMin() { return onlineExecuteWindowMin; }
    public int getOfflineExecuteWindowMin() { return offlineExecuteWindowMin; }
    public double getVisitRatio() { return visitRatio; }
    public int getDailyDhLikeCap() { return dailyDhLikeCap; }
    public int getDailyDhVisitCap() { return dailyDhVisitCap; }
    public String getLikeContentTemplates() { return likeContentTemplates; }

    public long getFeedTtlSeconds() { return feedTtlSeconds; }
    public long getQuotaTtlSeconds() { return quotaTtlSeconds; }
    public long getPrefTtlSeconds() { return prefTtlSeconds; }

    public int getOutboxScanLimit() { return outboxScanLimit; }
    public int getOutboxMaxAttempts() { return outboxMaxAttempts; }
    public int getDhTaskScanLimit() { return dhTaskScanLimit; }

    public int getDefaultCount() { return defaultCount; }
    public int getMaxCount() { return maxCount; }
    public int getMaxPageSize() { return maxPageSize; }

    /** Redis feed LIST feed 元素分隔符 */
    public String feedSeparator() {
        return ":";
    }

    /** feed 元素拆分 */
    public long[] parseFeedElement(String element) {
        if (element == null || element.isEmpty()) {
            return null;
        }
        String[] parts = element.split(feedSeparator());
        if (parts.length < 2) {
            return null;
        }
        try {
            long uid = Long.parseLong(parts[0]);
            int type = Integer.parseInt(parts[1]);
            return new long[]{uid, type};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String formatFeedElement(long targetUserId, int targetUserType) {
        return targetUserId + feedSeparator() + targetUserType;
    }

    public List<String> safeEmptyList() {
        return Collections.emptyList();
    }
}
