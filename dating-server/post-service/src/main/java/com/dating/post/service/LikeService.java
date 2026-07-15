package com.dating.post.service;

import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import com.dating.post.exception.PostNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LikeService.
 *
 * <p>负责点赞/取消点赞的业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final PostLikeManager postLikeManager;
    private final PostManager postManager;

    /**
     * 点赞/取消点赞.
     *
     * @param userId 用户ID
     * @param postId 帖子ID
     * @param like true=点赞, false=取消点赞
     * @return true=操作成功
     */
    public boolean actionLike(Long userId, Long postId, boolean like) {
        // 1. 校验帖子是否存在
        postManager.findByPostId(postId);

        // 2. 执行点赞/取消点赞
        boolean changed = postLikeManager.upsertLike(userId, postId, like);

        log.info("Like action: userId={} postId={} liked={} changed={}",
                userId, postId, like, changed);
        return true;
    }
}
