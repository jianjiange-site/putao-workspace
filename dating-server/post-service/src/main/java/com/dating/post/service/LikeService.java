package com.dating.post.service;

import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 点赞/取消点赞业务编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final PostLikeManager postLikeManager;
    private final PostManager postManager;

    public boolean actionLike(Long userId, Long postId, boolean like) {
        postManager.getByPostId(postId);
        boolean changed = postLikeManager.upsertLike(userId, postId, like);
        log.info("Like action: userId={} postId={} liked={} changed={}",
                userId, postId, like, changed);
        return true;
    }
}
