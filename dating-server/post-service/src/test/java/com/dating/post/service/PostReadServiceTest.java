package com.dating.post.service;

import com.dating.post.constant.PostStatus;
import com.dating.post.manager.PostBatchReadManager;
import com.dating.post.manager.PostDetailCacheManager;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.vo.PostDetailVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostReadServiceTest {

    @Mock
    private PostManager postManager;
    @Mock
    private PostStatManager statManager;
    @Mock
    private PostLikeManager likeManager;
    @Mock
    private PostBatchReadManager batchReadManager;
    @Mock
    private PostDetailCacheManager cacheManager;
    @Mock
    private RedissonClient redissonClient;

    @Test
    void shouldUseCachedCoreAndLoadOnlyDynamicFields() {
        PostReadService service = new PostReadService(
                postManager,
                statManager,
                likeManager,
                batchReadManager,
                cacheManager,
                redissonClient);
        when(cacheManager.get(10L)).thenReturn(
                new PostDetailCacheManager.CacheValue(
                        true, 10L, 20L, "hello", PostStatus.NORMAL,
                        List.of("a.jpg"), 100L));
        when(statManager.getCounts(10L)).thenReturn(new int[]{7, 3});
        when(likeManager.isLiked(30L, 10L)).thenReturn(true);

        PostDetailVO result = service.getPostDetail(10L, 30L);

        assertEquals("hello", result.getContent());
        assertEquals(7, result.getLikeCount());
        assertEquals(3, result.getCommentCount());
        assertTrue(result.getIsLiked());
        verify(postManager, never()).findByPostId(10L);
    }
}
