package com.dating.post.service;

import com.dating.post.constant.PostStatus;
import com.dating.post.entity.PostEntity;
import com.dating.post.exception.PostNotFoundException;
import com.dating.post.manager.PostBatchReadManager;
import com.dating.post.manager.PostDetailCacheManager;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.vo.PostDetailVO;
import com.dating.post.vo.UserPostsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.dating.post.constant.RedisKey.postDetailLoadLock;

/**
 * 帖子读取业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostReadService {

    private static final int MAX_PAGE_SIZE = 50;

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostLikeManager postLikeManager;
    private final PostBatchReadManager postBatchReadManager;
    private final PostDetailCacheManager postDetailCacheManager;
    private final RedissonClient redissonClient;

    public PostDetailVO getPostDetail(Long postId, Long currentUserId) {
        PostDetailCacheManager.CacheValue core = loadCore(postId);
        assertVisible(core, currentUserId);
        int[] counts = postStatManager.getCounts(postId);
        boolean isLiked = currentUserId != null
                && postLikeManager.isLiked(currentUserId, postId);
        return toDetail(core, counts, isLiked);
    }

    private PostDetailCacheManager.CacheValue loadCore(Long postId) {
        PostDetailCacheManager.CacheValue cached = postDetailCacheManager.get(postId);
        if (cached != null) {
            return requireFound(cached, postId);
        }

        RLock lock = null;
        boolean acquired = false;
        try {
            lock = redissonClient.getLock(postDetailLoadLock(postId));
            acquired = lock.tryLock(200, 5_000, TimeUnit.MILLISECONDS);

            // 无论是否拿到锁，都在等待后再查一次，避免热点 miss 并发回源。
            cached = postDetailCacheManager.get(postId);
            if (cached != null) {
                return requireFound(cached, postId);
            }
            return loadCoreFromDatabase(postId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loadCoreFromDatabase(postId);
        } catch (Exception e) {
            // Redis/Redisson 不可用时允许受控回源 DB。
            log.warn("Post detail cache lock unavailable, fallback to DB: postId={} error={}",
                    postId, e.getMessage());
            return loadCoreFromDatabase(postId);
        } finally {
            if (acquired && lock != null) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("Release post detail load lock failed: postId={} error={}",
                            postId, e.getMessage());
                }
            }
        }
    }

    private PostDetailCacheManager.CacheValue loadCoreFromDatabase(Long postId) {
        PostEntity post = postManager.findByPostId(postId);
        if (post == null) {
            postDetailCacheManager.putNotFound(postId);
            throw new PostNotFoundException(postId);
        }
        List<String> images = postManager.listImageKeys(postId);
        postDetailCacheManager.put(post, images);
        return PostDetailCacheManager.CacheValue.found(post, images);
    }

    private PostDetailCacheManager.CacheValue requireFound(
            PostDetailCacheManager.CacheValue cached, Long postId) {
        if (!cached.found()) {
            throw new PostNotFoundException(postId);
        }
        return cached;
    }

    private void assertVisible(PostDetailCacheManager.CacheValue core, Long currentUserId) {
        boolean normal = core.status() != null && core.status() == PostStatus.NORMAL;
        boolean owner = currentUserId != null && currentUserId.equals(core.userId());
        if (!normal && !owner) {
            throw new PostNotFoundException(core.postId());
        }
    }

    private PostDetailVO toDetail(PostDetailCacheManager.CacheValue core,
                                  int[] counts,
                                  boolean isLiked) {
        return PostDetailVO.builder()
                .postId(core.postId())
                .userId(core.userId())
                .content(core.content())
                .imageKeys(core.imageKeys())
                .likeCount(counts[0])
                .commentCount(counts[1])
                .isLiked(isLiked)
                .createdAt(core.createdAtEpoch())
                .build();
    }

    public UserPostsVO listUserPosts(Long userId, Long currentUserId,
                                     Long cursor, int requestedPageSize) {
        int pageSize = Math.max(1, Math.min(requestedPageSize, MAX_PAGE_SIZE));
        List<PostEntity> queried = postManager.listByUserIdWithCursor(
                userId, cursor, pageSize + 1);
        if (queried.isEmpty()) {
            return UserPostsVO.builder()
                    .items(new ArrayList<>())
                    .nextCursor(0L)
                    .hasMore(false)
                    .build();
        }

        boolean hasMore = queried.size() > pageSize;
        List<PostEntity> posts = queried.stream().limit(pageSize).toList();
        long nextCursor = hasMore
                ? posts.get(posts.size() - 1).getPostId()
                : 0L;

        List<Long> postIds = posts.stream().map(PostEntity::getPostId).toList();
        Map<Long, List<String>> imageMap = postBatchReadManager.listImageKeys(postIds);
        Map<Long, int[]> countMap = postBatchReadManager.getCounts(postIds);
        Map<Long, Boolean> likeStatusMap = currentUserId != null
                ? postLikeManager.batchIsLiked(currentUserId, postIds)
                : Map.of();

        List<PostDetailVO> items = posts.stream()
                .map(post -> {
                    int[] counts = countMap.getOrDefault(post.getPostId(), new int[]{0, 0});
                    return PostDetailVO.builder()
                            .postId(post.getPostId())
                            .userId(post.getUserId())
                            .content(post.getContent())
                            .imageKeys(imageMap.getOrDefault(post.getPostId(), List.of()))
                            .likeCount(counts[0])
                            .commentCount(counts[1])
                            .isLiked(likeStatusMap.getOrDefault(post.getPostId(), false))
                            .createdAt(post.getCreatedAt().getEpochSecond())
                            .build();
                })
                .toList();
        return UserPostsVO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }
}
