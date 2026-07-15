package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.constant.PostStatus;
import com.dating.post.constant.RedisKey;
import com.dating.post.entity.PostEntity;
import com.dating.post.entity.PostImageEntity;
import com.dating.post.entity.PostStatEntity;
import com.dating.post.exception.PostNotFoundException;
import com.dating.post.mapper.PostImageMapper;
import com.dating.post.mapper.PostMapper;
import com.dating.post.mapper.PostStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Post Manager.
 *
 * <p>负责 post + post_image 单表读写和 Redis 缓存操作.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostManager {

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final PostStatMapper postStatMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 创建帖子(事务外).
     *
     * @param postId 业务主键
     * @param userId 用户ID
     * @param content 内容
     * @return 帖子实体
     */
    public PostEntity createPost(Long postId, Long userId, String content) {
        PostEntity post = new PostEntity();
        post.setPostId(postId);
        post.setUserId(userId);
        post.setContent(content);
        post.setStatus(PostStatus.NORMAL);
        post.setDeleted(0);
        post.setCreatedAt(Instant.now());
        post.setUpdatedAt(Instant.now());

        postMapper.insert(post);
        log.debug("Post created: postId={} userId={}", postId, userId);
        return post;
    }

    /**
     * 批量保存帖子图片.
     *
     * @param postId 帖子ID
     * @param imageKeys 图片key列表
     */
    public void saveImages(Long postId, List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return;
        }

        List<PostImageEntity> images = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < imageKeys.size(); i++) {
            PostImageEntity image = new PostImageEntity();
            image.setPostId(postId);
            image.setSortOrder(i);
            image.setImageKey(imageKeys.get(i));
            image.setCreatedAt(now);
            images.add(image);
        }

        // 批量插入
        for (PostImageEntity image : images) {
            postImageMapper.insert(image);
        }
        log.debug("Post images saved: postId={} count={}", postId, images.size());
    }

    /**
     * 初始化帖子计数.
     *
     * @param postId 帖子ID
     */
    public void initStats(Long postId) {
        PostStatEntity stat = new PostStatEntity();
        stat.setPostId(postId);
        stat.setLikeCount(0);
        stat.setCommentCount(0);
        stat.setUpdatedAt(Instant.now());
        postStatMapper.insert(stat);
    }

    /**
     * 根据 postId 查询帖子.
     *
     * @param postId 业务主键
     * @return 帖子实体
     * @throws PostNotFoundException 帖子不存在
     */
    public PostEntity getByPostId(Long postId) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostEntity::getPostId, postId)
                .eq(PostEntity::getDeleted, 0);
        PostEntity post = postMapper.selectOne(wrapper);
        if (post == null) {
            throw new PostNotFoundException(postId);
        }
        return post;
    }

    /**
     * 根据 postId 查询帖子(可为空).
     *
     * @param postId 业务主键
     * @return 帖子实体或null
     */
    public PostEntity findByPostId(Long postId) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostEntity::getPostId, postId)
                .eq(PostEntity::getDeleted, 0);
        return postMapper.selectOne(wrapper);
    }

    /**
     * 查询用户的帖子列表(游标分页).
     *
     * @param userId 用户ID
     * @param cursor 游标(上一页最末的post_id)
     * @param pageSize 每页大小
     * @return 帖子列表
     */
    public List<PostEntity> listByUserIdWithCursor(Long userId, Long cursor, int pageSize) {
        if (cursor == null || cursor <= 0) {
            // 首次查询
            LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PostEntity::getUserId, userId)
                    .eq(PostEntity::getDeleted, 0)
                    .eq(PostEntity::getStatus, PostStatus.NORMAL)
                    .orderByDesc(PostEntity::getPostId)
                    .last("LIMIT " + pageSize);
            return postMapper.selectList(wrapper);
        } else {
            return postMapper.selectByUserIdWithCursor(userId, cursor, pageSize);
        }
    }

    /**
     * 查询用户的帖子列表(首次).
     *
     * @param userId 用户ID
     * @param pageSize 每页大小
     * @return 帖子列表
     */
    public List<PostEntity> listByUserId(Long userId, int pageSize) {
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostEntity::getUserId, userId)
                .eq(PostEntity::getDeleted, 0)
                .eq(PostEntity::getStatus, PostStatus.NORMAL)
                .orderByDesc(PostEntity::getPostId)
                .last("LIMIT " + pageSize);
        return postMapper.selectList(wrapper);
    }

    /**
     * 批量查询帖子.
     *
     * @param postIds postId列表
     * @return 帖子列表
     */
    public List<PostEntity> listByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PostEntity::getPostId, postIds)
                .eq(PostEntity::getDeleted, 0);
        return postMapper.selectList(wrapper);
    }

    /**
     * 查询近3天的帖子(Feed池重建用).
     *
     * @return 帖子列表
     */
    public List<PostEntity> listRecentPosts() {
        return postMapper.selectRecentPosts();
    }

    /**
     * 查询帖子图片.
     *
     * @param postId 帖子ID
     * @return 图片key列表
     */
    public List<String> listImageKeys(Long postId) {
        LambdaQueryWrapper<PostImageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostImageEntity::getPostId, postId)
                .orderByAsc(PostImageEntity::getSortOrder);
        List<PostImageEntity> images = postImageMapper.selectList(wrapper);
        return images.stream()
                .map(PostImageEntity::getImageKey)
                .toList();
    }

    /**
     * 逻辑删除帖子.
     *
     * @param postId 帖子ID
     */
    public void deletePost(Long postId) {
        PostEntity post = new PostEntity();
        post.setPostId(postId);
        post.setDeleted(1);
        post.setUpdatedAt(Instant.now());

        LambdaQueryWrapper<PostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PostEntity::getPostId, postId);
        postMapper.update(post, wrapper);

        // 删除图片
        LambdaQueryWrapper<PostImageEntity> imgWrapper = new LambdaQueryWrapper<>();
        imgWrapper.eq(PostImageEntity::getPostId, postId);
        postImageMapper.delete(imgWrapper);

        log.debug("Post deleted: postId={}", postId);
    }

    /**
     * 缓存帖子详情.
     *
     * @param postId 帖子ID
     * @param detail 详情数据
     */
    public void cachePostDetail(Long postId, Map<String, Object> detail) {
        String key = RedisKey.postDetail(postId);
        redisTemplate.opsForHash().putAll(key, detail);
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
    }

    /**
     * 获取缓存的帖子详情.
     *
     * @param postId 帖子ID
     * @return 详情数据或null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCachedPostDetail(Long postId) {
        String key = RedisKey.postDetail(postId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return null;
        }
        // StringRedisTemplate 的 hash key/field 都是 String,逐项转换以满足签名
        Map<String, Object> result = new java.util.HashMap<>(entries.size());
        entries.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /**
     * 删除帖子缓存.
     *
     * @param postId 帖子ID
     */
    public void deletePostCache(Long postId) {
        String key = RedisKey.postDetail(postId);
        redisTemplate.delete(key);
    }
}
