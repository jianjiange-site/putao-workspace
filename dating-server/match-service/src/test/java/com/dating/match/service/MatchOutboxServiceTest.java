package com.dating.match.service;

import com.dating.match.client.ImServiceClient;
import com.dating.match.client.UserServiceClient;
import com.dating.match.config.MatchProperties;
import com.dating.match.constant.OutboxActionConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.MatchOutboxEntity;
import com.dating.match.manager.MatchOutboxManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchOutboxServiceTest {
    @Mock
    private MatchOutboxManager outboxManager;
    @Mock
    private ImServiceClient imServiceClient;
    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private MatchProperties properties;
    private MatchOutboxService service;

    @BeforeEach
    void setUp() {
        service = new MatchOutboxService(
                outboxManager, imServiceClient, userServiceClient,
                new ObjectMapper(), properties);
        when(properties.getOutboxScanLimit()).thenReturn(100);
    }

    @Test
    void claimedDhOpeningReallyCallsImService() {
        MatchOutboxEntity task = new MatchOutboxEntity();
        task.setId(1L);
        task.setEventKey("88:dh-opening");
        task.setAction(OutboxActionConst.DH_OPENING);
        task.setPayloadJson("{\"user_id_a\":10,\"user_id_b\":20}");
        task.setAttempts(0);
        when(outboxManager.claimPending(any(Instant.class), anyInt(),
                anyString(), any(Instant.class))).thenReturn(List.of(task));
        when(userServiceClient.getUserType(10L)).thenReturn(UserTypeConst.BH);
        when(userServiceClient.getUserType(20L)).thenReturn(UserTypeConst.DH);
        when(imServiceClient.ensureConversation(10L, 20L)).thenReturn("conv-1");
        when(imServiceClient.triggerDhOpening(20L, 10L, "conv-1")).thenReturn(true);

        assertEquals(1, service.deliver());

        verify(imServiceClient).triggerDhOpening(20L, 10L, "conv-1");
        verify(outboxManager).markDone(eq(1L), anyString());
    }
}
