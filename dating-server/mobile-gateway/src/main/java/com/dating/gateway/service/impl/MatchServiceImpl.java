package com.dating.gateway.service.impl;

import com.dating.gateway.client.MatchClient;
import com.dating.gateway.dto.SuperHiReq;
import com.dating.gateway.dto.SwipeReq;
import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.MatchService;
import com.dating.gateway.vo.MatchCardVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class MatchServiceImpl implements MatchService {

    private final MatchClient matchClient;

    public MatchServiceImpl(MatchClient matchClient) {
        this.matchClient = matchClient;
    }

    @Override
    public List<MatchCardVO> getFeed(Long userId, int count) {
        try {
            return matchClient.getRecommendations(userId, count).stream()
                    .map(u -> MatchCardVO.builder()
                            .targetUserId(u.getUserId())
                            .nickname(u.getNickname())
                            .age(u.getAge())
                            .photoKeys(List.of(u.getAvatarKey()))
                            .build())
                    .toList();
        } catch (Exception e) {
            log.error("Failed to get match feed", e);
            throw new GatewayException(10901, "Failed to get match feed");
        }
    }

    @Override
    public boolean swipe(Long userId, SwipeReq req) {
        try {
            String action = "RIGHT".equalsIgnoreCase(req.getDirection()) ? "like" : "pass";
            return matchClient.matchAction(userId, req.getTargetUserId(), action).getIsMatched();
        } catch (Exception e) {
            log.error("Failed to swipe", e);
            throw new GatewayException(10901, "Failed to swipe");
        }
    }

    @Override
    public boolean superHi(Long userId, SuperHiReq req) {
        try {
            return matchClient.matchAction(userId, req.getTargetUserId(), "super_like").getIsMatched();
        } catch (Exception e) {
            log.error("Failed to super hi", e);
            throw new GatewayException(10901, "Failed to super hi");
        }
    }
}
