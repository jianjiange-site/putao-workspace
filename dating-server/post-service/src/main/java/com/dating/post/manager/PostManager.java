package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.PostStatus;
import com.dating.post.entity.PostEntity;
import com.dating.post.entity.PostImageEntity;
import com.dating.post.entity.PostStatEntity;
import com.dating.post.exception.PostNotFoundException;
import com.dating.post.mapper.PostImageMapper;
import com.dating.post.mapper.PostMapper;
import com.dating.post.mapper.PostStatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * posts、post_images、post_stats 的单表访问编排.
 */
@Component
@RequiredArgsConstructor
public class PostManager {

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final PostStatMapper postStatMapper;

    public PostEntity createPost(Long postId, Long userId, String content) {
        Instant now = Instant.now();
        PostEntity post = new PostEntity();
        post.setPostId(postId);
        post.setUserId(userId);
        post.setContent(content);
        post.setStatus(PostStatus.NORMAL);
        post.setDeleted(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);
        return post;
    }

    public void saveImages(Long postId, List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (int i = 0; i < imageKeys.size(); i++) {
            PostImageEntity image = new PostImageEntity();
            image.setPostId(postId);
            image.setSortOrder(i);
            image.setImageKey(imageKeys.get(i));
            image.setCreatedAt(now);
            postImageMapper.insert(image);
        }
    }

    public void initStats(Long postId) {
        PostStatEntity stat = new PostStatEntity();
        stat.setPostId(postId);
        stat.setLikeCount(0);
        stat.setCommentCount(0);
        stat.setUpdatedAt(Instant.now());
        postStatMapper.insert(stat);
    }

    public PostEntity getByPostId(Long postId) {
        PostEntity post = findByPostId(postId);
        if (post == null) {
            throw new PostNotFoundException(postId);
        }
        return post;
    }

    public PostEntity findByPostId(Long postId) {
        return postMapper.selectOne(
                new LambdaQueryWrapper<PostEntity>()
                        .eq(PostEntity::getPostId, postId)
                        .eq(PostEntity::getDeleted, 0));
    }

    public List<PostEntity> listByUserIdWithCursor(
            Long userId, Long cursor, int pageSize) {
        if (cursor == null || cursor <= 0) {
            return postMapper.selectList(
                    new LambdaQueryWrapper<PostEntity>()
                            .eq(PostEntity::getUserId, userId)
                            .eq(PostEntity::getDeleted, 0)
                            .eq(PostEntity::getStatus, PostStatus.NORMAL)
                            .orderByDesc(PostEntity::getPostId)
                            .last("LIMIT " + pageSize));
        }
        return postMapper.selectByUserIdWithCursor(userId, cursor, pageSize);
    }

    public List<PostEntity> listByUserId(Long userId, int pageSize) {
        return listByUserIdWithCursor(userId, 0L, pageSize);
    }

    public List<PostEntity> listByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return new ArrayList<>();
        }
        return postMapper.selectList(
                new LambdaQueryWrapper<PostEntity>()
                        .in(PostEntity::getPostId, postIds)
                        .eq(PostEntity::getDeleted, 0));
    }

    public List<PostEntity> listRecentPosts() {
        return postMapper.selectRecentPosts();
    }

    public List<String> listImageKeys(Long postId) {
        return postImageMapper.selectList(
                        new LambdaQueryWrapper<PostImageEntity>()
                                .eq(PostImageEntity::getPostId, postId)
                                .orderByAsc(PostImageEntity::getSortOrder))
                .stream()
                .map(PostImageEntity::getImageKey)
                .toList();
    }

    public void deletePost(Long postId) {
        PostEntity update = new PostEntity();
        update.setDeleted(1);
        update.setUpdatedAt(Instant.now());
        postMapper.update(update,
                new LambdaQueryWrapper<PostEntity>()
                        .eq(PostEntity::getPostId, postId)
                        .eq(PostEntity::getDeleted, 0));
        postImageMapper.delete(
                new LambdaQueryWrapper<PostImageEntity>()
                        .eq(PostImageEntity::getPostId, postId));
    }
}
