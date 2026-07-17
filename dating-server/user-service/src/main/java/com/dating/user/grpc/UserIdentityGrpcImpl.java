package com.dating.user.grpc;

import com.dating.user.constant.ErrorCode;
import com.dating.user.dto.ResolveOrCreateDTO;
import com.dating.user.exception.BizException;
import com.dating.user.proto.BanResult;
import com.dating.user.proto.CheckBanRequest;
import com.dating.user.proto.ResolveOrCreateByDeviceRequest;
import com.dating.user.proto.ResolveOrCreateByPhoneRequest;
import com.dating.user.proto.ResolveOrCreateByThirdPartyRequest;
import com.dating.user.proto.ResolveOrCreateResponse;
import com.dating.user.service.UserBanService;
import com.dating.user.service.UserIdentityService;
import com.dating.user.vo.BanStatusVO;
import com.dating.user.vo.ResolveOrCreateVO;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Identity gRPC impl — 解析或创建 + 封禁查询.
 *
 * <p>实际 gRPC 入口是 {@code @GrpcService} 标注的 {@link UserGrpcService},
 * 本类只提供方法实现,通过 mixin 方式被 {@link UserGrpcService} 调用.
 *
 * <p>本类不持有 StreamObserver 资源,仅做 dispatch + 异常转换.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserIdentityGrpcImpl {

    private final UserIdentityService identityService;
    private final UserBanService banService;

    /**
     * 手机号(短信登录)解析或创建.
     */
    public void resolveOrCreateByPhone(ResolveOrCreateByPhoneRequest request,
                                       StreamObserver<ResolveOrCreateResponse> responseObserver) {
        // 1. 入参校验
        if (request.getPhoneE164() == null || request.getPhoneE164().isBlank()) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "phoneE164 required"));
            return;
        }

        try {
            // 2. 走 service,DTO 内部已经 normalize(libphonenumber)
            ResolveOrCreateDTO dto = ResolveOrCreateDTO.builder()
                    .phoneE164(request.getPhoneE164())
                    .appName(request.getAppName().name())
                    .build();
            ResolveOrCreateVO vo = identityService.resolveOrCreateByPhone(dto);
            responseObserver.onNext(toProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("ResolveOrCreateByPhone failed phone={}", request.getPhoneE164(), ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 第三方授权解析或创建.
     */
    public void resolveOrCreateByThirdParty(ResolveOrCreateByThirdPartyRequest request,
                                            StreamObserver<ResolveOrCreateResponse> responseObserver) {
        if (request.getThirdPartyUserId() == null || request.getThirdPartyUserId().isBlank()) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "thirdPartyUserId required"));
            return;
        }
        try {
            ResolveOrCreateDTO dto = ResolveOrCreateDTO.builder()
                    .platform(request.getPlatform().getNumber())
                    .thirdPartyUserId(request.getThirdPartyUserId())
                    .appName(request.getAppName().name())
                    .googleEmail(request.getGoogleEmail())
                    .build();
            ResolveOrCreateVO vo = identityService.resolveOrCreateByThirdParty(dto);
            responseObserver.onNext(toProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("ResolveOrCreateByThirdParty failed platform={}", request.getPlatform(), ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 设备快速登录解析或创建.
     */
    public void resolveOrCreateByDevice(ResolveOrCreateByDeviceRequest request,
                                        StreamObserver<ResolveOrCreateResponse> responseObserver) {
        if (request.getDeviceId() == null || request.getDeviceId().isBlank()) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "deviceId required"));
            return;
        }
        try {
            ResolveOrCreateDTO dto = ResolveOrCreateDTO.builder()
                    .deviceId(request.getDeviceId())
                    .platform(request.getPlatform().getNumber())
                    .appName(request.getAppName().name())
                    .build();
            ResolveOrCreateVO vo = identityService.resolveOrCreateByDevice(dto);
            responseObserver.onNext(toProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("ResolveOrCreateByDevice failed deviceId={}", request.getDeviceId(), ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 查询用户封禁状态.
     */
    public void checkBan(CheckBanRequest request, StreamObserver<BanResult> responseObserver) {
        long userId = request.getUserId();
        if (userId <= 0) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "userId required"));
            return;
        }
        try {
            BanStatusVO vo = banService.checkBan(userId);
            BanResult.Builder b = BanResult.newBuilder()
                    .setBanned(Boolean.TRUE.equals(vo.getBanned()))
                    .setReason(toBanReason(vo.getReason()))
                    .setBannedAtMs(vo.getBannedAtMs() == null ? 0L : vo.getBannedAtMs())
                    .setMessage(vo.getMessage() == null ? "" : vo.getMessage());
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("CheckBan failed userId={}", userId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    // ========== helpers ==========

    private static ResolveOrCreateResponse toProto(ResolveOrCreateVO vo) {
        if (vo == null) {
            return ResolveOrCreateResponse.getDefaultInstance();
        }
        return ResolveOrCreateResponse.newBuilder()
                .setUserId(vo.getUserId() == null ? 0L : vo.getUserId())
                .setPending(Boolean.TRUE.equals(vo.getPending()))
                .setNewlyCreated(Boolean.TRUE.equals(vo.getNewlyCreated()))
                .build();
    }

    private static com.dating.user.proto.BanReason toBanReason(String reason) {
        if (reason == null) return com.dating.user.proto.BanReason.BAN_REASON_NONE;
        return switch (reason) {
            case "USER_BANNED" -> com.dating.user.proto.BanReason.BAN_REASON_USER_BANNED;
            case "USER_SUSPENDED" -> com.dating.user.proto.BanReason.BAN_REASON_USER_SUSPENDED;
            case "OPERATIONAL" -> com.dating.user.proto.BanReason.BAN_REASON_OPERATIONAL;
            default -> com.dating.user.proto.BanReason.BAN_REASON_NONE;
        };
    }

    private static void sendError(StreamObserver<?> observer, BizException ex) {
        UserGrpcService.sendBizError(observer, ex);
    }
}