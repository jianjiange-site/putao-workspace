package com.dating.match.service;

import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.MatchEntity;
import com.dating.match.entity.UserSwipeHistoryEntity;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.SuperHiOperationManager;
import com.dating.match.manager.UserSwipeHistoryManager;
import com.dating.match.vo.SwipeRespVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchTransactionServiceTest {
    @Mock
    private UserSwipeHistoryManager swipeHistoryManager;
    @Mock
    private LikeRecordManager likeRecordManager;
    @Mock
    private MatchManager matchManager;
    @Mock
    private MatchService matchService;
    @Mock
    private DhDelayedMatchService delayedMatchService;
    @Mock
    private SuperHiOperationManager superHiOperationManager;
    @InjectMocks
    private MatchTransactionService service;

    @Test
    void mutualBhSwipeUsesAuthoritativeMatchService() {
        UserSwipeHistoryEntity reverse = new UserSwipeHistoryEntity();
        reverse.setDirection(SwipeDirectionConst.RIGHT);
        when(swipeHistoryManager.findByPair(20L, 10L)).thenReturn(reverse);
        MatchEntity match = new MatchEntity();
        match.setId(99L);
        when(matchService.createMatch(10L, 20L, MatchSourceConst.SWIPE_MATCH))
                .thenReturn(match);

        SwipeRespVO result = service.recordSwipe(
                10L, 20L, UserTypeConst.BH, SwipeDirectionConst.RIGHT);

        assertEquals(99L, result.getMatchId());
        verify(matchService).createMatch(10L, 20L, MatchSourceConst.SWIPE_MATCH);
        verify(likeRecordManager, never()).upsert(
                any(Long.class), any(Long.class), any(Integer.class),
                any(Integer.class), any());
    }

    @Test
    void dhSwipePersistsDelayedTaskInsideTransactionBoundary() {
        SwipeRespVO result = service.recordSwipe(
                10L, 30L, UserTypeConst.DH, SwipeDirectionConst.RIGHT);

        assertEquals(null, result.getMatchId());
        verify(delayedMatchService).scheduleDelayedMatch(10L, 30L);
        verify(matchService, never()).createMatch(any(Long.class), any(Long.class), any());
    }
}
