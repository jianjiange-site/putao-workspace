package com.dating.match.client;

import com.dating.user.proto.BatchGetProfilesRequest;
import com.dating.user.proto.BatchGetProfilesResponse;
import com.dating.user.proto.BhCandidate;
import com.dating.user.proto.DhCandidate;
import com.dating.user.proto.GetUserTypeRequest;
import com.dating.user.proto.GetUserTypeResponse;
import com.dating.user.proto.ListDhCandidatesRequest;
import com.dating.user.proto.ListDhCandidatesResponse;
import com.dating.user.proto.NearbyUsersRequest;
import com.dating.user.proto.NearbyUsersResponse;
import com.dating.user.proto.UserProfileProto;
import com.dating.user.proto.UserServiceGrpc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * User Service gRPC Client.
 *
 * <p>match-service 通过本 Client 调用 user-service.严格不直连 user-service 的 PG/Redis.
 *
 * <p>故障策略:user-service 不可用时降级返回空集合(由调用方处理空集合语义).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserServiceClient {

    private final UserServiceGrpc.UserServiceBlockingStub userServiceBlockingStub;

    /**
     * 批量拉用户资料(展示卡片拼装用).
     *
     * @param userIds 用户 ID 列表
     * @return UserProfileProto 列表;user-service 不可用时返回空列表
     */
    public List<UserProfileProto> batchGetProfile(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            BatchGetProfilesRequest req = BatchGetProfilesRequest.newBuilder()
                    .addAllUserIds(userIds)
                    .setIncludeInterests(false)
                    .build();
            BatchGetProfilesResponse resp = userServiceBlockingStub.batchGetProfile(req);
            return resp.getProfilesList();
        } catch (Exception e) {
            log.warn("user-service.batchGetProfile failed, count={}, err={}", userIds.size(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询用户类型(BH=1/DH=2).失败时返回 -1,由调用方决定 fallback.
     */
    public int getUserType(long userId) {
        try {
            GetUserTypeRequest req = GetUserTypeRequest.newBuilder().setUserId(userId).build();
            GetUserTypeResponse resp = userServiceBlockingStub.getUserType(req);
            return resp.getUserType().getNumber();
        } catch (Exception e) {
            log.warn("user-service.getUserType failed, userId={}, err={}", userId, e.getMessage());
            return -1;
        }
    }

    /**
     * DH 召回(支持范围过滤 + exclude_user_ids).
     *
     * <p>D0 / D1 / LikeVisitor 三处共用入口.
     *
     * @return DH 候选列表;失败时返空
     */
    public List<DhCandidate> listDhCandidates(ListDhCandidatesRequest req) {
        try {
            ListDhCandidatesResponse resp = userServiceBlockingStub.listDhCandidates(req);
            return resp.getCandidatesList();
        } catch (Exception e) {
            log.warn("user-service.listDhCandidates failed, err={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 附近 BH 召回(基于 GEO).
     */
    public List<BhCandidate> nearbyUsers(NearbyUsersRequest req) {
        try {
            NearbyUsersResponse resp = userServiceBlockingStub.nearbyUsers(req);
            return resp.getCandidatesList();
        } catch (Exception e) {
            log.warn("user-service.nearbyUsers failed, err={}", e.getMessage());
            return Collections.emptyList();
        }
    }
}