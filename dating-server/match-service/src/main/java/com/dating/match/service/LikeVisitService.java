package com.dating.match.service;

import com.dating.match.constant.LikeVisitSourceConst;
import com.dating.match.constant.UserTypeConst;
import com.dating.match.entity.LikeRecordEntity;
import com.dating.match.entity.VisitRecordEntity;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.VisitRecordManager;
import com.dating.match.vo.LikeVO;
import com.dating.match.vo.ListLikesOfMeRespVO;
import com.dating.match.vo.ListVisitsOfMeRespVO;
import com.dating.match.vo.VisitVO;
import com.dating.user.proto.UserProfileProto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Like / Visit 列表 + 访问上报服务.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeVisitService {

    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final com.dating.match.client.UserServiceClient userServiceClient;

    /**
     * 拉"谁 like 了我"列表(按 liked_at DESC).
     */
    public ListLikesOfMeRespVO listLikesOfMe(long userId, int pageSize) {
        pageSize = Math.min(Math.max(1, pageSize), 50);
        // 简化:仅按 pageSize 取一页;真实分页需要游标(page_token = last liked_at_ms + id)
        List<LikeRecordEntity> entities = listLikesPaged(userId, pageSize);
        // 组装LikeVO列表
        List<LikeVO> likes = assembleLikes(entities);
        ListLikesOfMeRespVO resp = new ListLikesOfMeRespVO();
        resp.setLikes(likes);
        resp.setTotalUnread(likes.size()); // 简化:不区分 read/unread
        resp.setNextPageToken(likes.isEmpty() ? "" : String.valueOf(likes.get(likes.size() - 1).getLikedAtUnixMs()));
        return resp;
    }

    /**
     * 拉"谁访问了我"列表(按 visited_at DESC).
     */
    public ListVisitsOfMeRespVO listVisitsOfMe(long userId, int pageSize) {
        pageSize = Math.min(Math.max(1, pageSize), 50);
        List<VisitRecordEntity> entities = listVisitsPaged(userId, pageSize);
        List<VisitVO> visits = assembleVisits(entities);
        ListVisitsOfMeRespVO resp = new ListVisitsOfMeRespVO();
        resp.setVisits(visits);
        resp.setTotalUnread(visits.size());
        resp.setNextPageToken(visits.isEmpty() ? "" : String.valueOf(visits.get(visits.size() - 1).getVisitedAtUnixMs()));
        return resp;
    }

    /**
     * 异步记录访问(自访问短路;失败仅 WARN,不抛).
     */
    @Async("visitRecordExecutor")
    public void recordVisitAsync(long viewerUserId, long targetUserId) {
        if (viewerUserId == targetUserId) {
            return; // 自访问短路
        }
        try {
            visitRecordManager.upsert(viewerUserId, UserTypeConst.BH, targetUserId, LikeVisitSourceConst.PROFILE_VIEW);
        } catch (Exception e) {
            log.warn("recordVisit failed viewer={} target={} err={}", viewerUserId, targetUserId, e.getMessage());
        }
    }

    private List<LikeRecordEntity> listLikesPaged(long userId, int limit) {
        // 简化:直接 mapper listToUser
        return likeRecordManager.findToUser(userId, limit);
    }

    private List<VisitRecordEntity> listVisitsPaged(long userId, int limit) {
        return visitRecordManager.listToUser(userId, limit);
    }

    /**
     * 组装LikeVO列表
     */
    private List<LikeVO> assembleLikes(List<LikeRecordEntity> entities) {
        if (entities.isEmpty()) return List.of();
        List<Long> fromIds = entities.stream().map(LikeRecordEntity::getFromUserId).collect(Collectors.toList());
        // 调用userServiceClient.batchGetProfile(fromIds)，获取用户信息列表
        List<UserProfileProto> profiles = userServiceClient.batchGetProfile(fromIds);
        // 将用户信息列表转换为Map，key为userId，value为UserProfileProto
        Map<Long, UserProfileProto> byId = profiles.stream()
                .collect(Collectors.toMap(UserProfileProto::getUserId, p -> p, (a, b) -> a));
        // 遍历LikeRecordEntity列表
        List<LikeVO> result = new ArrayList<>(entities.size());
        for (LikeRecordEntity e : entities) {
            UserProfileProto p = byId.get(e.getFromUserId());
            LikeVO vo = new LikeVO();
            vo.setFromUserId(e.getFromUserId());
            vo.setLikedAtUnixMs(e.getLikedAt().toEpochMilli());
            vo.setLikeContent(e.getLikeContent() == null ? "" : e.getLikeContent());
            if (p != null) {
                vo.setNickname(p.getNickname() == null ? "" : p.getNickname());
                vo.setAge(p.getAge());
                if (p.getAvatar() != null && !p.getAvatar().getOriginalKey().isEmpty()) {
                    vo.setPhotoKeys(List.of(p.getAvatar().getOriginalKey()));
                } else {
                    vo.setPhotoKeys(new ArrayList<>());
                }
            }
            result.add(vo);
        }
        return result;
    }

    private List<VisitVO> assembleVisits(List<VisitRecordEntity> entities) {
        if (entities.isEmpty()) return List.of();
        List<Long> fromIds = entities.stream().map(VisitRecordEntity::getFromUserId).collect(Collectors.toList());
        List<UserProfileProto> profiles = userServiceClient.batchGetProfile(fromIds);
        Map<Long, UserProfileProto> byId = new HashMap<>();
        for (UserProfileProto p : profiles) {
            byId.put(p.getUserId(), p);
        }
        List<VisitVO> result = new ArrayList<>(entities.size());
        for (VisitRecordEntity e : entities) {
            UserProfileProto p = byId.get(e.getFromUserId());
            VisitVO vo = new VisitVO();
            vo.setFromUserId(e.getFromUserId());
            vo.setVisitedAtUnixMs(e.getVisitedAt().toEpochMilli());
            vo.setVisitCount(e.getVisitCount() == null ? 1 : e.getVisitCount());
            if (p != null) {
                vo.setNickname(p.getNickname() == null ? "" : p.getNickname());
                vo.setAge(p.getAge());
                if (p.getAvatar() != null && !p.getAvatar().getOriginalKey().isEmpty()) {
                    vo.setPhotoKeys(List.of(p.getAvatar().getOriginalKey()));
                } else {
                    vo.setPhotoKeys(new ArrayList<>());
                }
            }
            result.add(vo);
        }
        return result;
    }
}