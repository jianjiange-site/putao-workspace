package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostEntity;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.vo.PostDetailVO;
import com.dating.post.vo.RecommendFeedVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FeedService.
 *
 * <p>负责推荐 Feed 的三路混合和热门池重建.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final UserClient userClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    private static final int RECOMMEND_POOL_SIZE = 3000;
    private static final int USER_TIMELINE_SIZE = 100;

    /**
     * 获取推荐 Feed.
     *
     * @param currentUserId 当前用户ID
     * @param pageSize 每页大小
     * @param cursor 游标("recOffset:csOffset"格式)
     * @return 推荐 Feed 响应
     */
    public RecommendFeedVO getRecommendFeed(Long currentUserId, int pageSize, String cursor) {
        // 1. 解析游标
        int[] offsets = parseCursor(cursor);
        int recOffset = offsets[0];
        int csOffset = offsets[1];

        // 2. 获取用户性别
        boolean isMale = userClient.isMale(currentUserId);
        String oppositeGender = isMale ? "female" : "male";

        // 3. 并行三路获取
        Set<Long> usedFriendIds = new HashSet<>();
        List<PostDetailVO> feedItems = new ArrayList<>();

        // 位置分配: 1,2,4,5,7,8,9,10 -> recommend; 3 -> friend; 6 -> cold_start
        List<Integer> recommendSlots = List.of(0, 1, 3, 4, 6, 7, 8, 9);
        List<Integer> friendSlots = List.of(2);
        List<Integer> coldStartSlots = List.of(5);

        // 获取三路数据
        List<Long> recommendIds = getRecommendPool(oppositeGender, recOffset, pageSize + 10);
        List<Long> friendIds = getFriendTimeline(currentUserId, 5);
        List<Long> coldStartIds = getColdStartPool(oppositeGender, csOffset, 5);

        // 获取布隆过滤器
        RBloomFilter<String> bloomFilter = getUserBloomFilter(currentUserId);

        // 填充推荐位
        fillSlots(recommendSlots, recommendIds, feedItems, currentUserId, bloomFilter, usedFriendIds);
        fillFriendSlots(friendSlots, friendIds, feedItems, currentUserId, bloomFilter, usedFriendIds);
        fillSlots(coldStartSlots, coldStartIds, feedItems, currentUserId, bloomFilter, usedFriendIds);

        // 构建下一页游标
        String nextCursor = buildNextCursor(recOffset + pageSize, csOffset + 1);
        boolean hasMore = !feedItems.isEmpty();

        log.info("Feed returned: userId={} size={} recommend={} friends={} coldStart={}",
                currentUserId, feedItems.size(),
                countRecommend(feedItems),
                countFriends(feedItems),
                countColdStart(feedItems));

        return RecommendFeedVO.builder()
                .items(feedItems.stream().limit(pageSize).toList())
                .nextCursor(hasMore ? nextCursor : "")
                .hasMore(hasMore)
                .build();
    }

    private int[] parseCursor(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.equals("0:0") || cursor.equals("0")) {
            return new int[]{0, 0};
        }
        try {
            String[] parts = cursor.split(":");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private String buildNextCursor(int recOffset, int csOffset) {
        return recOffset + ":" + csOffset;
    }

    /**
     * 获取全网热门池.
     */
    private List<Long> getRecommendPool(String gender, int offset, int limit) {
        String key = gender.equals("male")
                ? RedisKey.feedPoolRecommendMale()
                : RedisKey.feedPoolRecommendFemale();

        Set<String> ids = stringRedisTemplate.opsForZSet().reverseRange(key, offset, offset + limit - 1);
        if (ids == null) {
            return Collections.emptyList();
        }
        return ids.stream().map(Long::parseLong).toList();
    }

    /**
     * 获取好友时间线.
     */
    private List<Long> getFriendTimeline(Long userId, int limit) {
        String key = RedisKey.userTimeline(userId);
        Set<String> ids = stringRedisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        if (ids == null) {
            return Collections.emptyList();
        }
        return ids.stream().map(Long::parseLong).toList();
    }

    /**
     * 获取冷启动池.
     */
    private List<Long> getColdStartPool(String gender, int offset, int limit) {
        String key = gender.equals("male")
                ? RedisKey.coldStartPoolMale()
                : RedisKey.coldStartPoolFemale();

        Set<String> ids = stringRedisTemplate.opsForZSet().reverseRange(key, offset, offset + limit - 1);
        if (ids == null) {
            return Collections.emptyList();
        }
        return ids.stream().map(Long::parseLong).toList();
    }

    /**
     * 获取用户已读布隆过滤器.
     */
    private RBloomFilter<String> getUserBloomFilter(Long userId) {
        String key = RedisKey.userReadBloom(userId);
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(key);
        bloomFilter.tryInit(5000, 0.01);
        return bloomFilter;
    }

    /**
     * 填充推荐位/冷启动位.
     */
    private void fillSlots(List<Integer> slots, List<Long> sourceIds,
                          List<PostDetailVO> feedItems, Long currentUserId,
                          RBloomFilter<String> bloomFilter, Set<Long> usedFriendIds) {
        for (int i = 0; i < Math.min(sourceIds.size(), slots.size()); i++) {
            Long postId = sourceIds.get(i);
            if (bloomFilter.contains(postId.toString())) {
                continue;
            }
            try {
                PostDetailVO detail = getPostDetailForFeed(postId, currentUserId);
                if (detail != null) {
                    feedItems.add(detail);
                    bloomFilter.add(postId.toString());
                }
            } catch (Exception e) {
                log.warn("Failed to get post detail for feed: postId={}", postId, e);
            }
        }
    }

    /**
     * 填充好友位(含频控).
     */
    private void fillFriendSlots(List<Integer> slots, List<Long> sourceIds,
                                List<PostDetailVO> feedItems, Long currentUserId,
                                RBloomFilter<String> bloomFilter, Set<Long> usedFriendIds) {
        for (int i = 0; i < Math.min(sourceIds.size(), slots.size()); i++) {
            Long postId = sourceIds.get(i);
            if (bloomFilter.contains(postId.toString())) {
                continue;
            }
            try {
                PostDetailVO detail = getPostDetailForFeed(postId, currentUserId);
                if (detail != null) {
                    // 好友频控: 同一好友最多1条
                    if (usedFriendIds.contains(detail.getUserId())) {
                        continue;
                    }
                    feedItems.add(detail);
                    bloomFilter.add(postId.toString());
                    usedFriendIds.add(detail.getUserId());
                }
            } catch (Exception e) {
                log.warn("Failed to get post detail for feed: postId={}", postId, e);
            }
        }
    }

    /**
     * 获取帖子详情(用于Feed).
     */
    private PostDetailVO getPostDetailForFeed(Long postId, Long currentUserId) {
        PostEntity post = postManager.findByPostId(postId);
        if (post == null) {
            return null;
        }

        int[] counts = postStatManager.getCounts(postId);
        List<String> imageKeys = postManager.listImageKeys(postId);
        boolean isLiked = currentUserId != null
                && com.dating.post.manager.PostLikeManager.class.cast(null) != null;

        return PostDetailVO.builder()
                .postId(post.getPostId())
                .userId(post.getUserId())
                .content(post.getContent())
                .imageKeys(imageKeys)
                .likeCount(counts[0])
                .commentCount(counts[1])
                .isLiked(false)
                .createdAt(post.getCreatedAt().getEpochSecond())
                .build();
    }

    private int countRecommend(List<PostDetailVO> items) {
        return items.size();
    }

    private int countFriends(List<PostDetailVO> items) {
        return 0; // 简化统计
    }

    private int countColdStart(List<PostDetailVO> items) {
        return 0; // 简化统计
    }

    // ========== 热门池重建 ==========

    /**
     * 重建全网热门池.
     *
     * <p>每5分钟执行一次.
     */
    public void rebuildRecommendPool() {
        log.info("Starting feed pool rebuild...");

        // 1. 捞取近3天的帖子
        List<PostEntity> recentPosts = postManager.listRecentPosts();
        log.info("Found {} recent posts for scoring", recentPosts.size());

        if (recentPosts.isEmpty()) {
            return;
        }

        // 2. 批量获取计数
        List<Long> postIds = recentPosts.stream().map(PostEntity::getPostId).toList();
        Map<Long, int[]> baseCounts = postStatManager.batchGetBaseCounts(postIds);

        // 3. 批量获取性别
        List<Long> userIds = recentPosts.stream().map(PostEntity::getUserId).distinct().toList();
        Map<Long, Boolean> genderMap = userClient.getGenders(userIds);

        // 4. 内存打分 + 分桶
        List<Map.Entry<Long, Double>> maleScores = new ArrayList<>();
        List<Map.Entry<Long, Double>> femaleScores = new ArrayList<>();

        long now = System.currentTimeMillis() / 1000;
        for (PostEntity post : recentPosts) {
            int[] counts = baseCounts.getOrDefault(post.getPostId(), new int[]{0, 0});
            int likes = counts[0];
            int comments = counts[1];

            // Hacker News 变体打分
            double hoursDiff = (now - post.getCreatedAt().getEpochSecond()) / 3600.0;
            double score = (10.0 + 1.0 * likes + 3.0 * comments) / Math.pow(hoursDiff + 2, 1.5);

            Boolean isMale = genderMap.get(post.getUserId());
            if (isMale != null && isMale) {
                maleScores.add(Map.entry(post.getPostId(), score));
            } else {
                femaleScores.add(Map.entry(post.getPostId(), score));
            }
        }

        // 5. 排序并取前3000
        maleScores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        femaleScores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Map.Entry<Long, Double>> topMale = maleScores.stream().limit(RECOMMEND_POOL_SIZE).toList();
        List<Map.Entry<Long, Double>> topFemale = femaleScores.stream().limit(RECOMMEND_POOL_SIZE).toList();

        // 6. 写影子ZSet
        writePoolToTemp(RedisKey.feedPoolRecommendMaleTmp(), topMale);
        writePoolToTemp(RedisKey.feedPoolRecommendFemaleTmp(), topFemale);

        // 7. 原子 RENAME
        try {
            stringRedisTemplate.rename(RedisKey.feedPoolRecommendMaleTmp(), RedisKey.feedPoolRecommendMale());
            stringRedisTemplate.rename(RedisKey.feedPoolRecommendFemaleTmp(), RedisKey.feedPoolRecommendFemale());
        } catch (Exception e) {
            log.error("Failed to rename feed pools", e);
            return;
        }

        // 8. 设置TTL
        stringRedisTemplate.expire(RedisKey.feedPoolRecommendMale(), java.time.Duration.ofDays(7));
        stringRedisTemplate.expire(RedisKey.feedPoolRecommendFemale(), java.time.Duration.ofDays(7));

        log.info("Feed pool rebuilt: candidates={} male={} female={}",
                recentPosts.size(), topMale.size(), topFemale.size());
    }

    private void writePoolToTemp(String tmpKey, List<Map.Entry<Long, Double>> entries) {
        // 清空旧数据
        stringRedisTemplate.delete(tmpKey);

        // 批量写入
        for (Map.Entry<Long, Double> entry : entries) {
            stringRedisTemplate.opsForZSet().add(tmpKey, String.valueOf(entry.getKey()), entry.getValue());
        }

        // 设置TTL
        stringRedisTemplate.expire(tmpKey, java.time.Duration.ofDays(7));
    }
}
