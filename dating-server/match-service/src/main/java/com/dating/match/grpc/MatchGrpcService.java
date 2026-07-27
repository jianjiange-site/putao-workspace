package com.dating.match.grpc;

import com.dating.match.constant.MatchErrorCode;
import com.dating.match.constant.SubscriptionTierConst;
import com.dating.match.constant.SwipeDirectionConst;
import com.dating.match.entity.MatchEntity;
import com.dating.match.exception.MatchBizException;
import com.dating.match.manager.MatchManager;
import com.dating.match.proto.Card;
import com.dating.match.proto.GetQuotaReq;
import com.dating.match.proto.GetQuotaResp;
import com.dating.match.proto.GetTodayFeedReq;
import com.dating.match.proto.GetTodayFeedResp;
import com.dating.match.proto.LikeVO;
import com.dating.match.proto.ListLikesOfMeReq;
import com.dating.match.proto.ListLikesOfMeResp;
import com.dating.match.proto.ListMatchesReq;
import com.dating.match.proto.ListMatchesResp;
import com.dating.match.proto.ListVisitsOfMeReq;
import com.dating.match.proto.ListVisitsOfMeResp;
import com.dating.match.proto.MatchServiceGrpc;
import com.dating.match.proto.MatchVO;
import com.dating.match.proto.RecordVisitReq;
import com.dating.match.proto.RecordVisitResp;
import com.dating.match.proto.SuperHiReq;
import com.dating.match.proto.SuperHiResp;
import com.dating.match.proto.SwipeReq;
import com.dating.match.proto.SwipeResp;
import com.dating.match.proto.TargetUserType;
import com.dating.match.proto.VisitVO;
import com.dating.match.service.FeedService;
import com.dating.match.service.LikeVisitService;
import com.dating.match.service.MatchService;
import com.dating.match.service.QuotaService;
import com.dating.match.service.SuperHiService;
import com.dating.match.service.SwipeService;
import com.dating.match.vo.CardVO;
import com.dating.match.vo.GetTodayFeedRespVO;
import com.dating.match.vo.ListLikesOfMeRespVO;
import com.dating.match.vo.ListVisitsOfMeRespVO;
import com.dating.match.vo.SuperHiRespVO;
import com.dating.match.vo.SwipeRespVO;
import com.dating.user.proto.UserProfileProto;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Match gRPC Service 实现(8 个 RPC,见 proto/match/match.proto).
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class MatchGrpcService extends MatchServiceGrpc.MatchServiceImplBase {

    private final FeedService feedService;
    private final SwipeService swipeService;
    private final SuperHiService superHiService;
    private final MatchService matchService;
    private final QuotaService quotaService;
    private final MatchManager matchManager;
    private final LikeVisitService likeVisitService;
    private final com.dating.match.client.UserServiceClient userServiceClient;
    private final com.dating.match.client.PaymentServiceClient paymentServiceClient;

    @Override
    public void getTodayFeed(GetTodayFeedReq request, StreamObserver<GetTodayFeedResp> responseObserver) {
        long userId = request.getUserId();
        int count = request.getCount();
        try {
            GetTodayFeedRespVO vo = feedService.getTodayFeed(userId, count);
            GetTodayFeedResp.Builder builder = GetTodayFeedResp.newBuilder()
                    .setExhausted(Boolean.TRUE.equals(vo.getExhausted()));
            for (CardVO card : vo.getCards()) {
                builder.addCards(toProto(card));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (MatchBizException e) {
            log.warn("getTodayFeed failed userId={} code={}", userId, e.getCode());
            responseObserver.onError(toGrpcStatus(e));
        } catch (Exception e) {
            log.error("getTodayFeed failed userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void swipe(SwipeReq request, StreamObserver<SwipeResp> responseObserver) {
        long userId = request.getUserId();
        long targetUserId = request.getTargetUserId();
        int direction;
        switch (request.getDirection()) {
            case LEFT -> direction = SwipeDirectionConst.LEFT;
            case RIGHT -> direction = SwipeDirectionConst.RIGHT;
            default -> {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("direction must be LEFT or RIGHT").asRuntimeException());
                return;
            }
        }
        try {
            SwipeRespVO vo = swipeService.swipe(userId, targetUserId, direction);
            SwipeResp resp = SwipeResp.newBuilder()
                    .setMatchId(vo.getMatchId() == null ? 0L : vo.getMatchId())
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (MatchBizException e) {
            responseObserver.onError(toGrpcStatus(e));
        } catch (Exception e) {
            log.error("swipe failed userId={} target={}", userId, targetUserId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void superHi(SuperHiReq request, StreamObserver<SuperHiResp> responseObserver) {
        long userId = request.getUserId();
        long targetUserId = request.getTargetUserId();
        try {
            SuperHiRespVO vo = superHiService.superHi(userId, targetUserId);
            SuperHiResp resp = SuperHiResp.newBuilder()
                    .setMatchId(vo.getMatchId() == null ? 0L : vo.getMatchId())
                    .setCoinsUsed(vo.getCoinsUsed() == null ? 0 : vo.getCoinsUsed())
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (MatchBizException e) {
            responseObserver.onError(toGrpcStatus(e));
        } catch (Exception e) {
            log.error("superHi failed userId={} target={}", userId, targetUserId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listMatches(ListMatchesReq request, StreamObserver<ListMatchesResp> responseObserver) {
        long userId = request.getUserId();
        int pageSize = request.getPageSize() > 0 ? Math.min(request.getPageSize(), 50) : 20;
        try {
            List<MatchEntity> matches = matchManager.listByUser(userId, pageSize);
            List<Long> partnerIds = new ArrayList<>();
            for (MatchEntity m : matches) {
                long otherId = m.getUserIdLow() == userId ? m.getUserIdHigh() : m.getUserIdLow();
                partnerIds.add(otherId);
            }
            List<UserProfileProto> profiles = userServiceClient.batchGetProfile(partnerIds);
            Map<Long, UserProfileProto> byId = profiles.stream()
                    .collect(Collectors.toMap(UserProfileProto::getUserId, p -> p, (a, b) -> a));

            ListMatchesResp.Builder builder = ListMatchesResp.newBuilder();
            for (MatchEntity m : matches) {
                long otherId = m.getUserIdLow() == userId ? m.getUserIdHigh() : m.getUserIdLow();
                UserProfileProto p = byId.get(otherId);
                MatchVO.Builder mb = MatchVO.newBuilder()
                        .setMatchId(m.getId())
                        .setPartnerUserId(otherId)
                        .setMatchedAtUnixMs(m.getMatchedAt() == null ? 0L : m.getMatchedAt().toEpochMilli())
                        .setSource(m.getSource() == null ? "" : m.getSource());
                if (p != null) {
                    mb.setPartnerNickname(p.getNickname() == null ? "" : p.getNickname());
                    if (p.getAvatar() != null && !p.getAvatar().getOriginalKey().isEmpty()) {
                        mb.addAllPartnerPhotoKeys(List.of(p.getAvatar().getOriginalKey()));
                    }
                }
                builder.addMatches(mb.build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("listMatches failed userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getQuota(GetQuotaReq request, StreamObserver<GetQuotaResp> responseObserver) {
        long userId = request.getUserId();
        try {
            int tier = paymentServiceClient.getSubscriptionTier(userId);
            QuotaService.QuotaSnapshot snap = quotaService.snapshot(userId, tier);
            GetQuotaResp resp = GetQuotaResp.newBuilder()
                    .setDailyRightSwipeLimit(snap.dailyRightSwipeLimit())
                    .setDailyRightSwipeUsed(snap.dailyRightSwipeUsed())
                    .setDailyCardLimit(snap.dailyCardLimit())
                    .setDailyCardUsed(snap.dailyCardUsed())
                    .setDailySuperHiLimit(snap.dailySuperHiLimit())
                    .setDailySuperHiUsed(snap.dailySuperHiUsed())
                    .setSuperHiCoinPrice(snap.superHiCoinPrice())
                    .setSubscriptionTier(tierName(snap.tier()))
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("getQuota failed userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listLikesOfMe(ListLikesOfMeReq request, StreamObserver<ListLikesOfMeResp> responseObserver) {
        long userId = request.getUserId();
        int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
        try {
            ListLikesOfMeRespVO vo = likeVisitService.listLikesOfMe(userId, pageSize);
            ListLikesOfMeResp.Builder builder = ListLikesOfMeResp.newBuilder()
                    .setNextPageToken(vo.getNextPageToken() == null ? "" : vo.getNextPageToken())
                    .setTotalUnread(vo.getTotalUnread() == null ? 0 : vo.getTotalUnread());
            for (com.dating.match.vo.LikeVO like : vo.getLikes()) {
                builder.addLikes(LikeVO.newBuilder()
                        .setFromUserId(like.getFromUserId() == null ? 0L : like.getFromUserId())
                        .setNickname(like.getNickname() == null ? "" : like.getNickname())
                        .setAge(like.getAge() == null ? 0 : like.getAge())
                        .addAllPhotoKeys(like.getPhotoKeys() == null ? List.of() : like.getPhotoKeys())
                        .setLikedAtUnixMs(like.getLikedAtUnixMs() == null ? 0L : like.getLikedAtUnixMs())
                        .setLikeContent(like.getLikeContent() == null ? "" : like.getLikeContent())
                        .build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("listLikesOfMe failed userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listVisitsOfMe(ListVisitsOfMeReq request, StreamObserver<ListVisitsOfMeResp> responseObserver) {
        long userId = request.getUserId();
        int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 20;
        try {
            ListVisitsOfMeRespVO vo = likeVisitService.listVisitsOfMe(userId, pageSize);
            ListVisitsOfMeResp.Builder builder = ListVisitsOfMeResp.newBuilder()
                    .setNextPageToken(vo.getNextPageToken() == null ? "" : vo.getNextPageToken())
                    .setTotalUnread(vo.getTotalUnread() == null ? 0 : vo.getTotalUnread());
            for (com.dating.match.vo.VisitVO visit : vo.getVisits()) {
                builder.addVisits(VisitVO.newBuilder()
                        .setFromUserId(visit.getFromUserId() == null ? 0L : visit.getFromUserId())
                        .setNickname(visit.getNickname() == null ? "" : visit.getNickname())
                        .setAge(visit.getAge() == null ? 0 : visit.getAge())
                        .addAllPhotoKeys(visit.getPhotoKeys() == null ? List.of() : visit.getPhotoKeys())
                        .setVisitedAtUnixMs(visit.getVisitedAtUnixMs() == null ? 0L : visit.getVisitedAtUnixMs())
                        .setVisitCount(visit.getVisitCount() == null ? 1 : visit.getVisitCount())
                        .build());
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("listVisitsOfMe failed userId={}", userId, e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    // 记录访客
    @Override
    public void recordVisit(RecordVisitReq request, StreamObserver<RecordVisitResp> responseObserver) {
        // 访客userId
        long viewer = request.getViewerUserId();
        // 目标userId
        long target = request.getTargetUserId();
        // 如果访客userId和目标userId相同，则直接返回
        if (viewer == target) {
            responseObserver.onNext(RecordVisitResp.newBuilder().setOk(true).build());
            responseObserver.onCompleted();
            return;
        }
        try {
            // 调用likeVisitService.recordVisitAsync(viewer, target)，记录访客
            likeVisitService.recordVisitAsync(viewer, target);
            // 组装RecordVisitResp响应
            responseObserver.onNext(RecordVisitResp.newBuilder().setOk(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("recordVisit enqueue failed viewer={} target={} err={}", viewer, target, e.getMessage());
            responseObserver.onNext(RecordVisitResp.newBuilder().setOk(false).build());
            responseObserver.onCompleted();
        }
    }

    // ========== helpers ==========

    private Card toProto(CardVO c) {
        Card.Builder b = Card.newBuilder()
                .setTargetUserId(c.getTargetUserId() == null ? 0L : c.getTargetUserId())
                .setTargetUserType(toTargetType(c.getTargetUserType()))
                .setNickname(c.getNickname() == null ? "" : c.getNickname())
                .setAge(c.getAge() == null ? 0 : c.getAge())
                .addAllPhotoKeys(c.getPhotoKeys() == null ? Collections.emptyList() : c.getPhotoKeys())
                .setBio(c.getBio() == null ? "" : c.getBio());
        if (c.getDistanceKm() != null) {
            b.setDistanceKm(c.getDistanceKm());
        }
        return b.build();
    }

    private TargetUserType toTargetType(Integer type) {
        if (type == null) return TargetUserType.TARGET_USER_TYPE_UNSPECIFIED;
        if (type == 1) return TargetUserType.BH;
        if (type == 2) return TargetUserType.DH;
        return TargetUserType.TARGET_USER_TYPE_UNSPECIFIED;
    }

    private String tierName(int tier) {
        return switch (tier) {
            case SubscriptionTierConst.FREE -> "FREE";
            case SubscriptionTierConst.WEEKLY -> "WEEKLY";
            case SubscriptionTierConst.MONTHLY -> "MONTHLY";
            case SubscriptionTierConst.YEARLY -> "YEARLY";
            default -> "FREE";
        };
    }

    private io.grpc.StatusRuntimeException toGrpcStatus(MatchBizException e) {
        io.grpc.Status status;
        switch (e.getCode()) {
            case MatchErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED,
                 MatchErrorCode.QUOTA_CARDS_EXCEEDED,
                 MatchErrorCode.SUPER_HI_INSUFFICIENT -> status = io.grpc.Status.RESOURCE_EXHAUSTED;
            case MatchErrorCode.TARGET_USER_NOT_FOUND,
                 MatchErrorCode.USER_NOT_FOUND -> status = io.grpc.Status.NOT_FOUND;
            case MatchErrorCode.SELF_OPERATION,
                 MatchErrorCode.SAME_GENDER_SWIPE -> status = io.grpc.Status.INVALID_ARGUMENT;
            case MatchErrorCode.CONCURRENT_SWIPE -> status = io.grpc.Status.ABORTED;
            case MatchErrorCode.MISSING_PARAMETER,
                 MatchErrorCode.PAGE_INVALID -> status = io.grpc.Status.INVALID_ARGUMENT;
            default -> status = io.grpc.Status.INTERNAL;
        }
        return status.withDescription(e.getMessage()).asRuntimeException();
    }
}