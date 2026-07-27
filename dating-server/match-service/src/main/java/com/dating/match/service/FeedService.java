package com.dating.match.service;

import com.dating.match.config.MatchProperties;
import com.dating.match.constant.MatchRedisKey;
import com.dating.match.constant.SubscriptionTierConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.exception.MatchBizException;
import com.dating.match.constant.MatchErrorCode;
import com.dating.match.vo.CardVO;
import com.dating.match.vo.GetTodayFeedRespVO;
import com.dating.user.proto.UserProfileProto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feed 队列消费服务(4.3).
 *
 * <p>核心流程:配额检查 → LPOP 弹出 → Redis SET 二次过滤 → 不足 while 循环继续 LPOP → 拼装 VO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final StringRedisTemplate stringRedisTemplate;
    private final QuotaService quotaService;
    private final ColdStartService coldStartService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final com.dating.match.client.PaymentServiceClient paymentServiceClient;
    private final MatchProperties props;

    /**
     * 拉取当日 feed.
     *
     * @param userId 用户 ID
     * @param count  期望卡片数(默认 5,上限 20)
     * @return feed 响应 VO;exhausted=true 时 cards 为空
     */
    public GetTodayFeedRespVO getTodayFeed(long userId, int count) {
        // 如果 count 小于等于 0，则设置为默认值
        if (count <= 0) {
            count = props.getDefaultCount();
        }
        if (count > props.getMaxCount()) {
            count = props.getMaxCount();
        }

        // 获取用户订阅等级
        int tier = paymentServiceClient.getSubscriptionTier(userId);

        // 1. 配额检查
        if (quotaService.isCardsExhausted(userId, tier)) {
            // 配额已用完
            GetTodayFeedRespVO empty = new GetTodayFeedRespVO();
            empty.setExhausted(true);
            empty.setCards(Collections.emptyList());
            return empty;
        }

        // 2. 计算需要拉取的卡片数
        QuotaService.QuotaSnapshot quota = quotaService.snapshot(userId, tier);
        // 剩余配额
        int remaining = quota.dailyCardLimit() - quota.dailyCardUsed();
        // 需要拉取的卡片数
        int need = Math.min(count, remaining);
        if (need <= 0) {
            // 剩余配额不足
            GetTodayFeedRespVO empty = new GetTodayFeedRespVO();
            empty.setExhausted(true);
            empty.setCards(Collections.emptyList());
            return empty;
        }

        // 2. while 循环:LPOP + 二次过滤
        List<CardVO> result = new ArrayList<>(need);
        int safetyRounds = 0;
        // 安全轮次
        while (result.size() < need && safetyRounds < 8) {
            // 安全轮次递增
            safetyRounds++;

            // 获取 feed 队列
            String feedKey = MatchRedisKey.feed(userId);
            // 需要拉取的卡片数
            int batchSize = need - result.size();
            // 拉取卡片
            List<String> batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
            if (batch == null || batch.isEmpty()) {
                // 队列空
                // 队列空 → 触发冷启动重建
                log.debug("Feed empty, rebuilding via cold start userId={}", userId);
                // 触发冷启动重建
                coldStartService.buildAndPush(userId);
                // 再次拉取卡片
                batch = stringRedisTemplate.opsForList().leftPop(feedKey, batchSize);
                // 队列空
                if (batch == null || batch.isEmpty()) {
                    break; // 极端兜底
                }
            }

            // 二次过滤:SMISMEMBER(已 swipe 的卡片)
            // 解析卡片:targetId / type
            List<Long> targetIds = new ArrayList<>(batch.size());
            List<Integer> types = new ArrayList<>(batch.size());
            for (String s : batch) {
                // 解析卡片
                long[] parsed = props.parseFeedElement(s);
                if (parsed == null) continue;
                targetIds.add(parsed[0]);
                types.add((int) parsed[1]);
            }
            if (targetIds.isEmpty()) continue;

            String swipedKey = MatchRedisKey.swiped(userId);
            // 已 swipe 的卡片
            List<Object> swipedArr = java.util.Arrays.asList(targetIds.toArray());
            // 已 swipe 的卡片命中
            java.util.List<Boolean> swipedHits = new java.util.ArrayList<>(swipedArr.size());
            // 遍历已 swipe 的卡片
            for (Object tid : swipedArr) {
                // 判断是否命中
                Boolean hit = stringRedisTemplate.opsForSet().isMember(swipedKey, tid);
                // 命中
                swipedHits.add(Boolean.TRUE.equals(hit));
            }

            // 把命中的丢弃,未命中加入结果
            // 遍历 batch
            for (int i = 0; i < batch.size() && result.size() < need; i++) {
                if (i >= swipedHits.size() || swipedHits.get(i)) continue;
                long tid = targetIds.get(i);
                int t = i < types.size() ? types.get(i) : UserTypeConst.BH;
                CardVO card = new CardVO();
                card.setTargetUserId(tid);
                card.setTargetUserType(t);
                card.setDistanceKm(t == UserTypeConst.DH ? -1.0 : null);
                result.add(card);
            }
        }

        // 3. 拼装 CardVO(nickname / age / photo_keys / distance)
        if (!result.isEmpty()) {
            List<Long> ids = result.stream().map(CardVO::getTargetUserId).collect(Collectors.toList());
            List<UserProfileProto> profiles = userServiceClient.batchGetProfile(ids);
            Map<Long, UserProfileProto> byId = profiles.stream()
                    .collect(Collectors.toMap(UserProfileProto::getUserId, p -> p, (a, b) -> a));
            for (CardVO card : result) {
                UserProfileProto p = byId.get(card.getTargetUserId());
                if (p != null) {
                    card.setNickname(p.getNickname() == null ? "" : p.getNickname());
                    card.setAge(p.getAge());
                    card.setBio(p.getBio() == null ? "" : p.getBio());
                    if (p.getAvatar() != null && !p.getAvatar().getOriginalKey().isEmpty()) {
                        card.setPhotoKeys(List.of(p.getAvatar().getOriginalKey()));
                    } else {
                        card.setPhotoKeys(Collections.emptyList());
                    }
                }
            }
        }

        GetTodayFeedRespVO resp = new GetTodayFeedRespVO();
        resp.setCards(result);
        resp.setExhausted(result.isEmpty());
        return resp;
    }

    /**
     * 同步 SADD 已 swipe target 到 Redis SET(供消费阶段二次过滤).
     */
    public void markSwiped(long userId, long targetUserId) {
        String swipedKey = MatchRedisKey.swiped(userId);
        stringRedisTemplate.opsForSet().add(swipedKey, String.valueOf(targetUserId));
    }
}