package com.dating.gateway.service.impl;

import com.dating.gateway.client.MatchClient;
import com.dating.gateway.client.UserClient;
import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.HomeService;
import com.dating.gateway.vo.HomeCardVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HomeServiceImpl implements HomeService {

    private final MatchClient matchClient;
    private final UserClient userClient;

    @Override
    public List<HomeCardVO> getHomeCards(Long userId, int pageSize) {
        try {
            var matchResp = matchClient.listMatches(userId);
            List<Long> userIds = matchResp.getMatchesList().stream()
                    .map(m -> m.getTargetUserId()).toList();

            if (userIds.isEmpty()) {
                return List.of();
            }

            var userResp = userClient.listUsers(userId, userIds);
            return matchResp.getMatchesList().stream().map(m -> {
                HomeCardVO vo = new HomeCardVO();
                vo.setTargetUserId(m.getTargetUserId());
                userResp.getUsersList().stream()
                        .filter(u -> u.getUserId().equals(m.getTargetUserId()))
                        .findFirst()
                        .ifPresent(u -> {
                            vo.setNickname(u.getNickname());
                            vo.setAge(u.getAge());
                            vo.setGender(u.getGender());
                            vo.setBio(u.getBio());
                            vo.setAvatar(u.getAvatar());
                        });
                return vo;
            }).toList();
        } catch (Exception e) {
            log.error("Failed to get home cards", e);
            throw new GatewayException(10401, "Failed to get home cards");
        }
    }
}
