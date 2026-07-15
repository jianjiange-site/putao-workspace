package com.dating.post.service;

import com.dating.post.entity.PostEntity;
import com.dating.post.exception.PostNotFoundException;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.vo.PostDetailVO;
import com.dating.post.vo.UserPostsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PostReadService.
 *
 * <p>负责帖子读取的业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostReadService {

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostLikeManager postLikeManager;

    /**
     * 获取帖子详情.
     *
     * @param postId 帖子ID
     * @param currentUserId 当前用户ID(可为null表示未登录)
     * @return 帖子详情
     */
    public PostDetailVO getPostDetail(Long postId, Long currentUserId) {
        // 1. 查询帖子
        PostEntity post = postManager.findByPostId(postId);
        if (post == null) {
            throw new PostNotFoundException(postId);
        }

        // 2. 查询计数
        int[] counts = postStatManager.getCounts(postId);

        // 3. 查询图片
        List<String> imageKeys = postManager.listImageKeys(postId);

        // 4. 查询点赞状态
        boolean isLiked = false;
        if (currentUserId != null) {
            isLiked = postLikeManager.isLiked(currentUserId, postId);
        }

        return PostDetailVO.builder()
                .postId(post.getPostId())
                .userId(post.getUserId())
                .content(post.getContent())
                .imageKeys(imageKeys)
                .likeCount(counts[0])
                .commentCount(counts[1])
                .isLiked(isLiked)
                .createdAt(post.getCreatedAt().getEpochSecond())
                .build();
    }

    /**
     * 获取用户帖子列表(游标分页).
     *
     * @param userId 目标用户ID
     * @param currentUserId 当前用户ID
     * @param cursor 游标
     * @param pageSize 每页大小
     * @return 用户帖子列表响应
     */
    public UserPostsVO listUserPosts(Long userId, Long currentUserId, Long cursor, int pageSize) {
        // 1. 查询帖子列表
        List<PostEntity> posts = postManager.listByUserIdWithCursor(userId, cursor, pageSize);

        if (posts.isEmpty()) {
            return UserPostsVO.builder()
                    .items(new ArrayList<>())
                    .nextCursor(0L)
                    .hasMore(false)
                    .build();
        }

        // 2. 判断是否有更多
        boolean hasMore = posts.size() >= pageSize;
        long nextCursor = hasMore ? posts.get(posts.size() - 1).getPostId() : 0L;

        // 3. 批量获取详情
        List<PostDetailVO> items = new ArrayList<>();
        List<Long> postIds = posts.stream().map(PostEntity::getPostId).toList();
        Map<Long, Boolean> likeStatusMap = currentUserId != null
                ? postLikeManager.batchIsLiked(currentUserId, postIds)
                : Map.of();

        for (PostEntity post : posts) {
            int[] counts = postStatManager.getCounts(post.getPostId());
            List<String> imageKeys = postManager.listImageKeys(post.getPostId());

            items.add(PostDetailVO.builder()
                    .postId(post.getPostId())
                    .userId(post.getUserId())
                    .content(post.getContent())
                    .imageKeys(imageKeys)
                    .likeCount(counts[0])
                    .commentCount(counts[1])
                    .isLiked(likeStatusMap.getOrDefault(post.getPostId(), false))
                    .createdAt(post.getCreatedAt().getEpochSecond())
                    .build());
        }

        return UserPostsVO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }
}
