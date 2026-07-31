package com.dating.match.service;

import com.dating.match.client.UserServiceClient;
import com.dating.match.constant.MatchSourceConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.DelayedMatchTaskEntity;
import com.dating.match.manager.DelayedMatchTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DhDelayedMatchServiceTest {
    @Mock
    private DelayedMatchTaskManager taskManager;
    @Mock
    private MatchService matchService;
    @Mock
    private UserServiceClient userServiceClient;
    @InjectMocks
    private DhDelayedMatchService service;

    @Test
    void claimedTaskCreatesMatchAndAcknowledgesLease() {
        DelayedMatchTaskEntity task = new DelayedMatchTaskEntity();
        task.setId(7L);
        task.setUserId(10L);
        task.setDhUserId(20L);
        task.setSource(MatchSourceConst.SWIPE_MATCH);
        task.setAttempts(0);
        when(taskManager.claimDue(any(Instant.class), anyInt(),
                anyString(), any(Instant.class))).thenReturn(List.of(task));
        when(userServiceClient.getUserType(20L)).thenReturn(UserTypeConst.DH);

        assertEquals(1, service.deliverDueTasks());

        verify(matchService).createMatch(10L, 20L, MatchSourceConst.SWIPE_MATCH);
        verify(taskManager).markDone(any(), anyString());
    }
}
