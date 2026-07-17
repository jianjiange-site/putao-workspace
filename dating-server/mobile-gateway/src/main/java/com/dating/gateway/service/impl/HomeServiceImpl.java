package com.dating.gateway.service.impl;

import com.dating.gateway.client.MatchClient;
import com.dating.gateway.client.UserClient;
import com.dating.gateway.service.HomeService;
import com.dating.gateway.vo.HomeCardVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HomeServiceImpl implements HomeService {

    private static final Logger log = LoggerFactory.getLogger(HomeServiceImpl.class);

    private final MatchClient matchClient;
    private final UserClient userClient;

    public HomeServiceImpl(MatchClient matchClient, UserClient userClient) {
        this.matchClient = matchClient;
        this.userClient = userClient;
    }

    @Override
    public List<HomeCardVO> getHomeCards(Long userId, int pageSize) {
        try {
            var recommendations = matchClient.getRecommendations(userId, pageSize);
            if (recommendations.isEmpty()) {
                return List.of();
            }

            List<Long> userIds = recommendations.stream()
                    .map(r -> r.getUserId())
                    .toList();

            var userProfiles = userClient.batchGetUserProfiles(userIds);

            return recommendations.stream().map(rec -> {
                HomeCardVO vo = new HomeCardVO();
                vo.setTargetUserId(rec.getUserId());
                vo.setNickname(rec.getNickname());
                vo.setAge(rec.getAge());
                vo.setBio("");
                vo.setAvatar(rec.getAvatarKey());
                return vo;
            }).toList();
        } catch (Exception e) {
            log.error("Failed to get home cards", e);
            return List.of();
        }
    }
}
