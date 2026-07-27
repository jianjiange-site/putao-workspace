package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.DhInteractionConst;
import com.dating.match.constant.LikeVisitSourceConst;
import com.dating.match.constant.MatchRedisKey;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.DhInteractionTaskEntity;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.VisitRecordManager;
import com.dating.match.recommend.CandidateRecaller;
import com.dating.user.proto.DhCandidate;
import com.dating.user.proto.ListDhCandidatesRequest;
import com.dating.user.proto.UserProfileProto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DH 互动任务生成 + 执行(6.3 共享).
 *
 * <p>{@link #runOnlinePlan()} / {@link #runOfflinePlan()} / {@link #runExecutor()}
 * 分别由 OnlinePlanGenerator / OfflinePlanGenerator / LikeVisitorTaskExecutor scheduler 调用.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhInteractionPlanService {

    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final com.dating.match.client.ImServiceClient imServiceClient;
    private final DhInteractionTaskManager taskManager;
    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final CandidateRecaller recaller;
    private final MatchProperties props;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private final Random random = new Random();

    // ========== Generator ==========

    /**
     * OnlinePlanGenerator 入口.
     */
    public int runOnlinePlan() {
        long now = Instant.now().toEpochMilli();
        long cursor = readOnlineCursor(now);
        long maxLookback = now - 30 * 60 * 1000L; // 30 分钟
        // 如果cursor小于maxLookback，则重置cursor
        if (cursor < maxLookback) {
            log.warn("Online cursor too old, reset: cursor={} now={}", cursor, now);
            cursor = now - 60 * 1000L;
        }

        // 获取在线用户列表
        List<Long> onlineUserIds = imServiceClient.listOnlineUsers(cursor, now, 5000);
        // 写入cursor
        writeOnlineCursor(now);

        int total = 0;
        for (Long userId : onlineUserIds) {
            try {
                // 生成DH互动任务
                total += generateOne(userId, DhInteractionConst.SCENE_ONLINE);
            } catch (Exception e) {
                log.warn("Online plan generate failed userId={} err={}", userId, e.getMessage());
            }
        }
        return total;
    }

    /**
     * OfflinePlanGenerator 入口.
     */
    public int runOfflinePlan() {
        long now = Instant.now().toEpochMilli();
        long offlineThresholdMs = props.getOfflineThresholdSeconds() * 1000L;
        long offlineUntil = now - offlineThresholdMs;
        long cursor = readOfflineCursor(offlineUntil);
        long lookbackFloor = offlineUntil - props.getOfflineLookbackSeconds() * 1000L;
        long minLowerBound = Math.max(cursor, lookbackFloor);

        List<Long> offlineUserIds = imServiceClient.listRecentOfflineUsers(minLowerBound, offlineUntil, 5000);
        writeOfflineCursor(offlineUntil);

        int total = 0;
        for (Long userId : offlineUserIds) {
            try {
                total += generateOne(userId, DhInteractionConst.SCENE_OFFLINE);
            } catch (Exception e) {
                log.warn("Offline plan generate failed userId={} err={}", userId, e.getMessage());
            }
        }
        return total;
    }

    /**
     * 给单个用户生成 ONLINE / OFFLINE 计划.
     */
    private int generateOne(long userId, int scene) {
        // 1. 类型闸:必须 BH
        int type = userServiceClient.getUserType(userId);
        if (type != UserTypeConst.BH) {
            return 0;
        }

        // 2.1 cooldown(仅 ONLINE)
        if (scene == DhInteractionConst.SCENE_ONLINE) {
            //在线cooldown（冷却）检查
            Boolean has = stringRedisTemplate.hasKey(MatchRedisKey.dhPlanCooldown(userId));
            if (Boolean.TRUE.equals(has)) {
                return 0;
            }
        } else {
            // OFFLINE:lastScene 闸（上次场景）检查，防止刚在线上生成过任务，下线后马上又在离线生成任务
            String lastScene = stringRedisTemplate.opsForValue().get(MatchRedisKey.dhPlanLastScene(userId));
            if ("OFFLINE".equals(lastScene)) {
                return 0;
            }
        }
        // 2.2 任务表去重
        if (taskManager.existsByScene(userId, scene)) {
            return 0;
        }

        // 3. 用户画像中心点
        List<UserProfileProto> profiles = userServiceClient.batchGetProfile(List.of(userId));
        if (profiles.isEmpty()) {
            return 0;
        }
        UserProfileProto user = profiles.get(0);
        int targetGender = CandidateRecaller.oppositeGender(user.getGender().getNumber());
        int userAge = user.getAge() > 0 ? user.getAge() : 25;

        // 4. exclude:已 swipe 的 DH
        List<Long> exclude = new ArrayList<>(recaller.excludeUserIds(userId));

        // 5. 24h 上限检查
        Instant since24h = Instant.now().minus(Duration.ofHours(24));
        long dailyLike = likeRecordManager.countDhLikeSince(userId, since24h);
        long dailyVisit = visitRecordManager.countDhVisitSince(userId, since24h);
        int likeRemaining = (int) Math.max(0, props.getDailyDhLikeCap() - dailyLike);
        int visitRemaining = (int) Math.max(0, props.getDailyDhVisitCap() - dailyVisit);
        if (likeRemaining == 0 && visitRemaining == 0) {
            return 0;
        }

        // 6. 决定本次生成数
        int countRange = scene == DhInteractionConst.SCENE_ONLINE
                ? randomBetween(props.getOnlineCountMin(), props.getOnlineCountMax())
                : randomBetween(props.getOfflineCountMin(), props.getOfflineCountMax());

        // 7. 召回 DH(单层,简单范围)
        ListDhCandidatesRequest req = ListDhCandidatesRequest.newBuilder()
                .setTargetGender(targetGender)
                .setAgeMin(Math.max(18, userAge - 10))
                .setAgeMax(userAge + 10)
                .setBeautyMin(0)
                .setBeautyMax(100)
                .addAllExcludeUserIds(exclude)
                .setLimit(countRange * 2)
                .build();
        // 调用userServiceClient.listDhCandidates(req)，获取候选人列表
        List<DhCandidate> dhs = userServiceClient.listDhCandidates(req);
        if (dhs.isEmpty()) {
            return 0;
        }
        // 随机打乱候选人列表
        Collections.shuffle(dhs);
        // 取前countRange个候选人，作为本次生成任务的候选人列表
        List<DhCandidate> picks = dhs.subList(0, Math.min(countRange, dhs.size()));

        // 8. 按 visit/like 比例分配
        int visitTarget = (int) Math.round(picks.size() * props.getVisitRatio());
        visitTarget = Math.min(visitTarget, visitRemaining);
        int likeTarget = picks.size() - visitTarget;
        likeTarget = Math.min(likeTarget, likeRemaining);
        if (visitTarget + likeTarget == 0) {
            return 0;
        }

        // 9. execute_time 在 [now, now + windowMin] 均匀随机
        int windowMin = scene == DhInteractionConst.SCENE_ONLINE
                ? props.getOnlineExecuteWindowMin()
                : props.getOfflineExecuteWindowMin();
        Instant fireStart = Instant.now();
        Instant fireEnd = fireStart.plus(Duration.ofMinutes(windowMin));

        List<DhInteractionTaskEntity> tasks = new ArrayList<>();
        for (int i = 0; i < picks.size() && (visitTarget + likeTarget) > 0; i++) {
            DhCandidate dh = picks.get(i);
            int action;
            String content = null;
            // 按比例 60/40:前 60% VISIT,后 40% LIKE
            if (i < visitTarget) {
                action = DhInteractionConst.ACTION_VISIT;
                visitTarget--;
            } else {
                action = DhInteractionConst.ACTION_LIKE;
                content = randomLikeContent(targetGender);
                likeTarget--;
            }

            // 组装DhInteractionTaskEntity
            DhInteractionTaskEntity task = new DhInteractionTaskEntity();
            // 发起userId
            task.setFromUserId(dh.getUserId());
            // 目标userId
            task.setToUserId(userId);
            // 动作
            task.setAction(action);
            // 场景
            task.setScene(scene);
            // 执行时间
            task.setExecuteTime(randomInstant(fireStart, fireEnd));
            // like内容
            task.setLikeContent(content);
            tasks.add(task);
        }
        if (!tasks.isEmpty()) {
            taskManager.batchInsert(tasks);
        }

        // 10. 收尾:cooldown / lastScene
        if (scene == DhInteractionConst.SCENE_ONLINE) {
            // 写入cooldown
            stringRedisTemplate.opsForValue().set(
                    MatchRedisKey.dhPlanCooldown(userId),
                    "1",
                    Duration.ofSeconds(props.getOnlineCooldownSeconds()));
        }
        // 写入lastScene
        stringRedisTemplate.opsForValue().set(
                MatchRedisKey.dhPlanLastScene(userId),
                scene == DhInteractionConst.SCENE_ONLINE ? "ONLINE" : "OFFLINE");

        return tasks.size();
    }

    // ========== Executor ==========

    /**
     * LikeVisitorTaskExecutor 入口.
     */
    public int runExecutor() {
        Instant now = Instant.now();
        // 扫描到期的任务
        List<DhInteractionTaskEntity> due = taskManager.scanDueTasks(now, props.getDhTaskScanLimit());
        // 执行任务数量
        int executed = 0;
        for (DhInteractionTaskEntity t : due) {
            try {
                // 执行任务
                executeOne(t);
                taskManager.hardDelete(t.getId());
                executed++;
            } catch (Exception e) {
                log.warn("Execute DH task failed: id={} err={}", t.getId(), e.getMessage());
                // 不删行,下一轮继续重试
            }
        }
        return executed;
    }

    /**
     * 执行单条任务.
     */
    @Transactional(rollbackFor = Exception.class)
    public void executeOne(DhInteractionTaskEntity t) {
        //如果场景为在线，则sourceCode为DH_PLAN_ONLINE，否则为DH_PLAN_OFFLINE
        int sourceCode = t.getScene() == DhInteractionConst.SCENE_ONLINE
                ? LikeVisitSourceConst.DH_PLAN_ONLINE
                : LikeVisitSourceConst.DH_PLAN_OFFLINE;
        //决定是插入like记录还是visit记录
        if (t.getAction() == DhInteractionConst.ACTION_LIKE) {
            likeRecordManager.upsert(t.getFromUserId(), t.getToUserId(),
                    UserTypeConst.DH, sourceCode, t.getLikeContent());
        } else if (t.getAction() == DhInteractionConst.ACTION_VISIT) {
            visitRecordManager.upsert(t.getFromUserId(), UserTypeConst.DH,
                    t.getToUserId(), sourceCode);
        }
    }

    // ========== cursor helpers ==========

    private long readOnlineCursor(long now) {
        String v = stringRedisTemplate.opsForValue().get(MatchRedisKey.DH_PLAN_CURSOR_ONLINE);
        if (v == null || v.isEmpty()) {
            return now - 60_000L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return now - 60_000L;
        }
    }

    private void writeOnlineCursor(long ms) {
        stringRedisTemplate.opsForValue().set(MatchRedisKey.DH_PLAN_CURSOR_ONLINE, String.valueOf(ms));
    }

    private long readOfflineCursor(long fallback) {
        String v = stringRedisTemplate.opsForValue().get(MatchRedisKey.DH_PLAN_CURSOR_OFFLINE);
        if (v == null || v.isEmpty()) {
            return Instant.now().minus(Duration.ofHours(3)).toEpochMilli();
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return Instant.now().minus(Duration.ofHours(3)).toEpochMilli();
        }
    }

    private void writeOfflineCursor(long ms) {
        stringRedisTemplate.opsForValue().set(MatchRedisKey.DH_PLAN_CURSOR_OFFLINE, String.valueOf(ms));
    }

    // ========== utils ==========

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private Instant randomInstant(Instant start, Instant end) {
        long startMs = start.toEpochMilli();
        long endMs = end.toEpochMilli();
        long randomMs = ThreadLocalRandom.current().nextLong(startMs, endMs + 1);
        return Instant.ofEpochMilli(randomMs);
    }

    /**
     * 随机取一条 like 文案(从 Nacos JSON 配置).
     */
    private String randomLikeContent(int targetGender) {
        String raw = props.getLikeContentTemplates();
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return "Hi, nice to meet you!";
        }
        try {
            List<LikeTemplate> templates = objectMapper.readValue(raw, new TypeReference<List<LikeTemplate>>() {});
            if (templates.isEmpty()) {
                return "Hi!";
            }
            // 简单过滤:gender_pref 匹配(0/1/2 都不限),随机抽一条
            List<LikeTemplate> filtered = new ArrayList<>();
            for (LikeTemplate t : templates) {
                if (t.genderPref == 0 || t.genderPref == targetGender) {
                    filtered.add(t);
                }
            }
            if (filtered.isEmpty()) {
                return templates.get(ThreadLocalRandom.current().nextInt(templates.size())).content;
            }
            return filtered.get(ThreadLocalRandom.current().nextInt(filtered.size())).content;
        } catch (Exception e) {
            log.warn("Parse like_content_templates failed, use default. err={}", e.getMessage());
            return "Hi!";
        }
    }

    /** like_content 模板结构. */
    public record LikeTemplate(String content, int genderPref) {
    }
}