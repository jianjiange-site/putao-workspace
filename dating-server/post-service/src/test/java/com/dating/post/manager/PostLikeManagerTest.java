package com.dating.post.manager;

import com.dating.post.mapper.PostLikeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostLikeManagerTest {

    @Mock
    private PostLikeMapper mapper;
    @Mock
    private PostStatManager statManager;

    @Test
    void shouldNotIncrementWhenUpsertIsIdempotent() {
        PostLikeManager manager = new PostLikeManager(mapper, statManager);
        when(mapper.upsertLike(1L, 2L, 1)).thenReturn(0);

        assertFalse(manager.upsertLike(1L, 2L, true));

        verify(statManager, never()).incrRedisLike(2L, 1);
    }

    @Test
    void shouldIncrementOnceWhenStateChanged() {
        PostLikeManager manager = new PostLikeManager(mapper, statManager);
        when(mapper.upsertLike(1L, 2L, 1)).thenReturn(1);

        assertTrue(manager.upsertLike(1L, 2L, true));

        verify(statManager).incrRedisLike(2L, 1);
    }
}
