package com.dating.gateway.service.impl;

import com.dating.gateway.client.MatchClient;
import com.dating.gateway.dto.SuperHiReq;
import com.dating.gateway.dto.SwipeReq;
import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.MatchService;
import com.dating.gateway.vo.MatchCardVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MatchServiceImpl implements MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchServiceImpl.class);

    private final MatchClient matchClient;

    public MatchServiceImpl(MatchClient matchClient) {
        this.matchClient = matchClient;
    }

    @Override
    public List<MatchCardVO> getFeed(Long userId, int count) {
        try {
            var recommendations = matchClient.getRecommendations(userId, count);
            List<MatchCardVO> result = new ArrayList<>();
            for (var u : recommendations) {
                MatchCardVO vo = new MatchCardVO();
                vo.setTargetUserId(u.getUserId());
                vo.setNickname(u.getNickname());
                vo.setAge(u.getAge());
                vo.setPhotoKeys(List.of(u.getAvatarKey()));
                result.add(vo);
            }
            return result;
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

    @Override
    public List<MatchCardVO> getMatches(Long userId) {
        return List.of();
    }

    @Override
    public List<MatchCardVO> getSwipeHistory(Long userId, int pageSize, long cursor) {
        return List.of();
    }
}
