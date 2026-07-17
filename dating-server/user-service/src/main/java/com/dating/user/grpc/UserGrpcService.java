package com.dating.user.grpc;

import com.dating.user.config.UserContext;
import com.dating.user.exception.BizException;
import com.dating.user.exception.UserBannedException;
import com.dating.user.exception.UserNotFoundException;
import com.dating.user.proto.UserServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * User gRPC 服务实现入口.
 *
 * <p>继承生成的 {@link UserServiceGrpc.UserServiceImplBase},把单个 Service 拆分到
 * 两个 {@code *GrpcImpl} 实现类以降低单个文件体量:
 * <ul>
 *     <li>{@link UserIdentityGrpcImpl} — ResolveOrCreate* / CheckBan(放在 mixin)</li>
 *     <li>{@link UserProfileGrpcImpl} — Profile / Interest / Avatar 全包</li>
 * </ul>
 *
 * <p>本身只是 mixin 宿主,业务逻辑全部委托给对应 grpc impl.
 */
@Slf4j
@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final UserIdentityGrpcImpl identityMixin;
    private final UserProfileGrpcImpl profileMixin;

    public UserGrpcService(UserIdentityGrpcImpl identityMixin, UserProfileGrpcImpl profileMixin) {
        this.identityMixin = identityMixin;
        this.profileMixin = profileMixin;
    }

    // ===== Identity =====

    @Override
    public void resolveOrCreateByPhone(com.dating.user.proto.ResolveOrCreateByPhoneRequest request,
                                       StreamObserver<com.dating.user.proto.ResolveOrCreateResponse> responseObserver) {
        identityMixin.resolveOrCreateByPhone(request, responseObserver);
    }

    @Override
    public void resolveOrCreateByThirdParty(com.dating.user.proto.ResolveOrCreateByThirdPartyRequest request,
                                            StreamObserver<com.dating.user.proto.ResolveOrCreateResponse> responseObserver) {
        identityMixin.resolveOrCreateByThirdParty(request, responseObserver);
    }

    @Override
    public void resolveOrCreateByDevice(com.dating.user.proto.ResolveOrCreateByDeviceRequest request,
                                        StreamObserver<com.dating.user.proto.ResolveOrCreateResponse> responseObserver) {
        identityMixin.resolveOrCreateByDevice(request, responseObserver);
    }

    @Override
    public void checkBan(com.dating.user.proto.CheckBanRequest request,
                         StreamObserver<com.dating.user.proto.BanResult> responseObserver) {
        identityMixin.checkBan(request, responseObserver);
    }

    // ===== Profile =====

    @Override
    public void getProfile(com.dating.user.proto.GetUserProfileRequest request,
                           StreamObserver<com.dating.user.proto.UserProfileProto> responseObserver) {
        profileMixin.getProfile(request, responseObserver);
    }

    @Override
    public void batchGetProfile(com.dating.user.proto.BatchGetProfilesRequest request,
                                StreamObserver<com.dating.user.proto.BatchGetProfilesResponse> responseObserver) {
        profileMixin.batchGetProfile(request, responseObserver);
    }

    @Override
    public void getUserProfile(com.dating.user.proto.GetUserProfileRequest request,
                               StreamObserver<com.dating.user.proto.UserProfileResponse> responseObserver) {
        profileMixin.getUserProfile(request, responseObserver);
    }

    @Override
    public void updateUserProfile(com.dating.user.proto.UpdateUserProfileRequest request,
                                  StreamObserver<com.dating.user.proto.UserProfileResponse> responseObserver) {
        profileMixin.updateUserProfile(request, responseObserver);
    }

    @Override
    public void batchGetUserProfiles(com.dating.user.proto.BatchGetUserProfilesRequest request,
                                      StreamObserver<com.dating.user.proto.BatchGetUserProfilesResponse> responseObserver) {
        profileMixin.batchGetUserProfiles(request, responseObserver);
    }

    @Override
    public void updateProfile(com.dating.user.proto.UpdateUserProfileRequest request,
                              StreamObserver<com.dating.user.proto.UserProfileProto> responseObserver) {
        profileMixin.updateProfile(request, responseObserver);
    }

    @Override
    public void upsertOnboarding(com.dating.user.proto.UpsertOnboardingRequest request,
                                 StreamObserver<com.dating.user.proto.OnboardingResponse> responseObserver) {
        profileMixin.upsertOnboarding(request, responseObserver);
    }

    @Override
    public void replaceUserInterests(com.dating.user.proto.ReplaceUserInterestsRequest request,
                                     StreamObserver<com.dating.user.proto.ReplaceUserInterestsResponse> responseObserver) {
        profileMixin.replaceUserInterests(request, responseObserver);
    }

    @Override
    public void presignAvatarUpload(com.dating.user.proto.PresignAvatarUploadRequest request,
                                    StreamObserver<com.dating.user.proto.PresignAvatarUploadResponse> responseObserver) {
        profileMixin.presignAvatarUpload(request, responseObserver);
    }

    @Override
    public void confirmAvatarUpload(com.dating.user.proto.ConfirmAvatarUploadRequest request,
                                    StreamObserver<com.dating.user.proto.ConfirmAvatarUploadResponse> responseObserver) {
        profileMixin.confirmAvatarUpload(request, responseObserver);
    }

    /**
     * 把 BizException 翻译成 gRPC StatusRuntimeException,网关 client 会还原成 Result.
     */
    protected static void sendBizError(StreamObserver<?> observer, BizException ex) {
        io.grpc.Status status;
        if (ex instanceof UserNotFoundException) {
            status = io.grpc.Status.NOT_FOUND;
        } else if (ex instanceof UserBannedException) {
            status = io.grpc.Status.PERMISSION_DENIED;
        } else {
            // 通用 400
            status = io.grpc.Status.INVALID_ARGUMENT;
        }
        observer.onError(status
                .withDescription(ex.getGrpcDescription())
                .asRuntimeException());
    }

    protected static Long requireCallerUserId() {
        Long userId = UserContext.callerUserId();
        if (userId == null) {
            throw new BizException(com.dating.user.constant.ErrorCode.BAD_REQUEST, "userId missing in metadata");
        }
        return userId;
    }
}