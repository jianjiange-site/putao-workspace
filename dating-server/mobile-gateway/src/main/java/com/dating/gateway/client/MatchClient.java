package com.dating.gateway.client;

import com.dating.match.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class MatchClient {

    @Value("${match.service.grpc.host:localhost}")
    private String matchServiceHost;

    @Value("${match.service.grpc.port:19092}")
    private int matchServicePort;

    private MatchServiceGrpc.MatchServiceBlockingStub createStub() {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(matchServiceHost, matchServicePort)
                .usePlaintext()
                .build();
        return MatchServiceGrpc.newBlockingStub(channel);
    }

    public MatchResponse matchAction(Long fromUserId, Long toUserId, String action) {
        log.info("matchAction: from={}, to={}, action={}", fromUserId, toUserId, action);
        MatchRequest request = MatchRequest.newBuilder()
                .setFromUserId(fromUserId)
                .setToUserId(toUserId)
                .setAction(action)
                .build();
        return createStub().matchAction(request);
    }

    public List<RecommendedUser> getRecommendations(Long userId, int limit) {
        log.info("getRecommendations for userId={}, limit={}", userId, limit);
        GetRecommendationsRequest request = GetRecommendationsRequest.newBuilder()
                .setUserId(userId).setLimit(limit).build();
        GetRecommendationsResponse response = createStub().getRecommendations(request);
        return response.getUsersList();
    }
}
