package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.constant.PostStatus;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostEntity;
import com.dating.post.manager.PostBatchReadManager;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import com.dating.post.vo.PostDetailVO;
import com.dating.post.vo.RecommendFeedVO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 三路推荐 Feed 和热门池重建.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private static final int RECOMMEND_POOL_SIZE = 3_000;
    private static final int MAX_PAGE_SIZE = 50;

    @Getter
    private enum FeedSource {
        RECOMMEND("recommend"),
        FRIEND("friend"),
        COLD_START("cold_start");

        private final String name;

        FeedSource(String name) {
            this.name = name;
        }
    }

    private record Candidate(long postId, FeedSource source) {}

    private final PostManager postManager;
    private final PostBatchReadManager postBatchReadManager;
    private final PostLikeManager postLikeManager;
    private final UserClient userClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public RecommendFeedVO getRecommendFeed(
            Long currentUserId, int requestedPageSize, String cursor) {
        int pageSize = Math.max(1, Math.min(requestedPageSize, MAX_PAGE_SIZE));
        int[] offsets = parseCursor(cursor);
        int recOffset = offsets[0];
        int coldOffset = offsets[1];

        boolean isMale = userClient.isMale(currentUserId);
        String targetGender = isMale ? "female" : "male";

        int fetchSize = pageSize * 3;
        ArrayDeque<Long> recommend = new ArrayDeque<>(
                getRecommendPool(targetGender, recOffset, fetchSize));
        ArrayDeque<Long> friends = new ArrayDeque<>(
                getFriendTimeline(currentUserId, pageSize));
        ArrayDeque<Long> coldStart = new ArrayDeque<>(
                getColdStartPool(targetGender, coldOffset, fetchSize));

        RBloomFilter<String> bloomFilter = getUserBloomFilter(currentUserId);
        List<Candidate> candidates = selectCandidates(
                pageSize * 2, recommend, friends, coldStart);
        Map<Long, PostDetailVO> details = loadDetails(
                candidates.stream().map(Candidate::postId).toList(),
                currentUserId);

        List<PostDetailVO> items = new ArrayList<>();
        Map<FeedSource, Integer> sourceCount = new EnumMap<>(FeedSource.class);
        for (FeedSource source : FeedSource.values()) {
            sourceCount.put(source, 0);
        }
        int recExamined = 0;
        int coldExamined = 0;

        for (Candidate candidate : candidates) {
            if (candidate.source() == FeedSource.RECOMMEND) {
                recExamined++;
            } else if (candidate.source() == FeedSource.COLD_START) {
                coldExamined++;
            }

            String bloomValue = String.valueOf(candidate.postId());
            if (bloomFilter.contains(bloomValue)) {
                continue;
            }
            PostDetailVO detail = details.get(candidate.postId());
            if (detail == null) {
                continue;
            }
            items.add(detail);
            bloomFilter.add(bloomValue);
            sourceCount.merge(candidate.source(), 1, Integer::sum);
            if (items.size() >= pageSize) {
                break;
            }
        }

        boolean hasMore = !items.isEmpty()
                && (items.size() == pageSize || candidates.size() > items.size());
        String nextCursor = hasMore
                ? buildNextCursor(recOffset + recExamined, coldOffset + coldExamined)
                : "";

        log.info("Feed returned: userId={} size={} recommend={} friends={} coldStart={}",
                currentUserId,
                items.size(),
                sourceCount.get(FeedSource.RECOMMEND),
                sourceCount.get(FeedSource.FRIEND),
                sourceCount.get(FeedSource.COLD_START));
        return RecommendFeedVO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 每 10 个位置按 8 recommend + 第3位 friend + 第6位 cold_start 混排。
     * 指定来源为空时优先降级到 recommend，再使用其余来源补齐。
     */
    private List<Candidate> selectCandidates(
            int limit,
            ArrayDeque<Long> recommend,
            ArrayDeque<Long> friends,
            ArrayDeque<Long> coldStart) {
        List<Candidate> result = new ArrayList<>();
        Set<Long> selected = new HashSet<>();

        while (result.size() < limit
                && (!recommend.isEmpty() || !friends.isEmpty() || !coldStart.isEmpty())) {
            int slot = result.size() % 10 + 1;
            FeedSource preferred = slot == 3
                    ? FeedSource.FRIEND
                    : slot == 6 ? FeedSource.COLD_START : FeedSource.RECOMMEND;

            Candidate candidate = poll(preferred, recommend, friends, coldStart);
            if (candidate == null) {
                candidate = poll(FeedSource.RECOMMEND, recommend, friends, coldStart);
            }
            if (candidate == null) {
                candidate = poll(FeedSource.COLD_START, recommend, friends, coldStart);
            }
            if (candidate == null) {
                candidate = poll(FeedSource.FRIEND, recommend, friends, coldStart);
            }
            if (candidate == null) {
                break;
            }
            if (selected.add(candidate.postId())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private Candidate poll(FeedSource source,
                           ArrayDeque<Long> recommend,
                           ArrayDeque<Long> friends,
                           ArrayDeque<Long> coldStart) {
        ArrayDeque<Long> queue = switch (source) {
            case RECOMMEND -> recommend;
            case FRIEND -> friends;
            case COLD_START -> coldStart;
        };
        Long postId = queue.pollFirst();
        return postId == null ? null : new Candidate(postId, source);
    }

    private Map<Long, PostDetailVO> loadDetails(
            List<Long> postIds, Long currentUserId) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(postIds));
        List<PostEntity> posts = postManager.listByPostIds(distinctIds);
        Map<Long, List<String>> images = postBatchReadManager.listImageKeys(distinctIds);
        Map<Long, int[]> counts = postBatchReadManager.getCounts(distinctIds);
        Map<Long, Boolean> likes = postLikeManager.batchIsLiked(
                currentUserId, distinctIds);

        Map<Long, PostDetailVO> result = new HashMap<>();
        for (PostEntity post : posts) {
            if (!Objects.equals(post.getStatus(), PostStatus.NORMAL)) {
                continue;
            }
            int[] postCounts = counts.getOrDefault(post.getPostId(), new int[]{0, 0});
            result.put(post.getPostId(), PostDetailVO.builder()
                    .postId(post.getPostId())
                    .userId(post.getUserId())
                    .content(post.getContent())
                    .imageKeys(images.getOrDefault(post.getPostId(), List.of()))
                    .likeCount(postCounts[0])
                    .commentCount(postCounts[1])
                    .isLiked(likes.getOrDefault(post.getPostId(), false))
                    .createdAt(post.getCreatedAt().getEpochSecond())
                    .build());
        }
        return result;
    }

    private int[] parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()
                || "0".equals(cursor) || "0:0".equals(cursor)) {
            return new int[]{0, 0};
        }
        try {
            String[] parts = cursor.split(":");
            return new int[]{
                    Math.max(0, Integer.parseInt(parts[0])),
                    Math.max(0, Integer.parseInt(parts[1]))
            };
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private String buildNextCursor(int recOffset, int coldOffset) {
        return recOffset + ":" + coldOffset;
    }

    private List<Long> getRecommendPool(String gender, int offset, int limit) {
        String key = "male".equals(gender)
                ? RedisKey.feedPoolRecommendMale()
                : RedisKey.feedPoolRecommendFemale();
        return reverseRange(key, offset, limit);
    }

    private List<Long> getFriendTimeline(Long userId, int limit) {
        return reverseRange(RedisKey.userTimeline(userId), 0, limit);
    }

    private List<Long> getColdStartPool(String gender, int offset, int limit) {
        String key = "male".equals(gender)
                ? RedisKey.coldStartPoolMale()
                : RedisKey.coldStartPoolFemale();
        return reverseRange(key, offset, limit);
    }

    private List<Long> reverseRange(String key, int offset, int limit) {
        Set<String> ids = stringRedisTemplate.opsForZSet()
                .reverseRange(key, offset, offset + limit - 1L);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream().map(Long::parseLong).toList();
    }

    private RBloomFilter<String> getUserBloomFilter(Long userId) {
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(
                RedisKey.userReadBloom(userId));
        boolean initialized = bloomFilter.tryInit(5_000, 0.01);
        if (initialized || bloomFilter.remainTimeToLive() < 0) {
            bloomFilter.expire(Duration.ofDays(7));
        }
        return bloomFilter;
    }

    /**
     * 每 5 分钟重建近 3 天热门池.
     */
    public void rebuildRecommendPool() {
        List<PostEntity> recentPosts = postManager.listRecentPosts();
        if (recentPosts.isEmpty()) {
            return;
        }

        List<Long> postIds = recentPosts.stream().map(PostEntity::getPostId).toList();
        Map<Long, int[]> counts = postBatchReadManager.getCounts(postIds);
        List<Long> userIds = recentPosts.stream()
                .map(PostEntity::getUserId).distinct().toList();
        Map<Long, Boolean> genders = userClient.getGenders(userIds);

        List<Map.Entry<Long, Double>> maleScores = new ArrayList<>();
        List<Map.Entry<Long, Double>> femaleScores = new ArrayList<>();
        long now = System.currentTimeMillis() / 1_000;
        for (PostEntity post : recentPosts) {
            int[] postCounts = counts.getOrDefault(post.getPostId(), new int[]{0, 0});
            double hours = Math.max(0,
                    (now - post.getCreatedAt().getEpochSecond()) / 3_600.0);
            double score = (10.0 + postCounts[0] + 3.0 * postCounts[1])
                    / Math.pow(hours + 2, 1.5);
            (genders.getOrDefault(post.getUserId(), false)
                    ? maleScores : femaleScores)
                    .add(Map.entry(post.getPostId(), score));
        }

        maleScores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        femaleScores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        replacePool(
                RedisKey.feedPoolRecommendMaleTmp(),
                RedisKey.feedPoolRecommendMale(),
                maleScores.stream().limit(RECOMMEND_POOL_SIZE).toList());
        replacePool(
                RedisKey.feedPoolRecommendFemaleTmp(),
                RedisKey.feedPoolRecommendFemale(),
                femaleScores.stream().limit(RECOMMEND_POOL_SIZE).toList());
        log.info("Feed pools rebuilt: candidates={} male={} female={}",
                recentPosts.size(),
                Math.min(maleScores.size(), RECOMMEND_POOL_SIZE),
                Math.min(femaleScores.size(), RECOMMEND_POOL_SIZE));
    }

    private void replacePool(String tempKey, String finalKey,
                             List<Map.Entry<Long, Double>> entries) {
        stringRedisTemplate.delete(tempKey);
        if (entries.isEmpty()) {
            stringRedisTemplate.delete(finalKey);
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (Map.Entry<Long, Double> entry : entries) {
            tuples.add(new DefaultTypedTuple<>(
                    String.valueOf(entry.getKey()), entry.getValue()));
        }
        stringRedisTemplate.opsForZSet().add(tempKey, tuples);
        stringRedisTemplate.expire(tempKey, Duration.ofDays(7));
        stringRedisTemplate.rename(tempKey, finalKey);
        stringRedisTemplate.expire(finalKey, Duration.ofDays(7));
    }
}
