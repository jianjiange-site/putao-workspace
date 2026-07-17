package com.dating.user.grpc;

import com.dating.user.constant.ErrorCode;
import com.dating.user.dto.ConfirmAvatarDTO;
import com.dating.user.dto.OnboardingDTO;
import com.dating.user.dto.PresignAvatarDTO;
import com.dating.user.dto.UpdateProfileDTO;
import com.dating.user.exception.BizException;
import com.dating.user.proto.AvatarProto;
import com.dating.user.proto.BatchGetProfilesRequest;
import com.dating.user.proto.BatchGetProfilesResponse;
import com.dating.user.proto.BatchGetUserProfilesRequest;
import com.dating.user.proto.BatchGetUserProfilesResponse;
import com.dating.user.proto.ConfirmAvatarUploadRequest;
import com.dating.user.proto.ConfirmAvatarUploadResponse;
import com.dating.user.proto.Gender;
import com.dating.user.proto.GetUserProfileRequest;
import com.dating.user.proto.OnboardingResponse;
import com.dating.user.proto.PresignAvatarUploadRequest;
import com.dating.user.proto.PresignAvatarUploadResponse;
import com.dating.user.proto.ReplaceUserInterestsRequest;
import com.dating.user.proto.ReplaceUserInterestsResponse;
import com.dating.user.proto.UpdateUserProfileRequest;
import com.dating.user.proto.UpsertOnboardingRequest;
import com.dating.user.proto.UserInterestProto;
import com.dating.user.proto.UserProfileProto;
import com.dating.user.proto.UserProfileResponse;
import com.dating.user.service.UserAvatarService;
import com.dating.user.service.UserInterestService;
import com.dating.user.service.UserProfileService;
import com.dating.user.vo.AvatarVO;
import com.dating.user.vo.PresignResultVO;
import com.dating.user.vo.UserInterestVO;
import com.dating.user.vo.UserProfileVO;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Profile gRPC impl — Profile / Interest / Avatar 全包.
 *
 * <p>故意用手写 proto builder,不引入 MapStruct protobuf 扩展(踩设计文档红线 6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileGrpcImpl {

    private final UserProfileService profileService;
    private final UserInterestService interestService;
    private final UserAvatarService avatarService;

    // ===== Profile =====

    /**
     * 单用户读取(主资料 + 兴趣 + 头像).
     */
    public void getProfile(GetUserProfileRequest request,
                           StreamObserver<UserProfileProto> responseObserver) {
        long userId = request.getUserId();
        if (userId <= 0) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "userId required"));
            return;
        }
        try {
            UserProfileVO vo = profileService.getProfile(userId);
            responseObserver.onNext(toProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("GetProfile failed userId={}", userId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 批量读取(≤200/次).
     */
    public void batchGetProfile(BatchGetProfilesRequest request,
                                StreamObserver<BatchGetProfilesResponse> responseObserver) {
        List<Long> ids = request.getUserIdsList();
        if (ids == null || ids.isEmpty()) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "userIds empty"));
            return;
        }
        if (ids.size() > 200) {
            sendError(responseObserver, new BizException(ErrorCode.BATCH_TOO_LARGE, "max 200"));
            return;
        }
        try {
            List<UserProfileVO> vos = profileService.batchGetProfile(ids, request.getIncludeInterests());
            BatchGetProfilesResponse.Builder b = BatchGetProfilesResponse.newBuilder();
            for (UserProfileVO vo : vos) {
                b.addProfiles(toProto(vo));
            }
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("BatchGetProfile failed count={}", ids.size(), ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 老版简单接口(沿用,post-service 在用).
     */
    public void getUserProfile(GetUserProfileRequest request,
                               StreamObserver<UserProfileResponse> responseObserver) {
        long userId = request.getUserId();
        if (userId <= 0) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "userId required"));
            return;
        }
        try {
            UserProfileVO vo = profileService.getProfile(userId);
            responseObserver.onNext(toLegacyProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("GetUserProfile(legacy) failed userId={}", userId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 老版修改接口(沿用).
     */
    public void updateUserProfile(UpdateUserProfileRequest request,
                                  StreamObserver<UserProfileResponse> responseObserver) {
        Long callerId = UserGrpcService.requireCallerUserId();
        if (request.getUserId() > 0 && request.getUserId() != callerId) {
            sendError(responseObserver, new BizException(ErrorCode.FORBIDDEN, "cannot modify others"));
            return;
        }
        try {
            UpdateProfileDTO dto = UpdateProfileDTO.builder()
                    .userId(callerId)
                    .nickname(emptyToNull(request.getNickname()))
                    .age(request.getAge() == 0 ? null : request.getAge())
                    .preferredLocation(emptyToNull(request.getPreferredLocation()))
                    .bio(emptyToNull(request.getBio()))
                    .occupation(emptyToNull(request.getOccupation()))
                    .education(emptyToNull(request.getEducation()))
                    .height(request.getHeight() == 0 ? null : request.getHeight())
                    .build();
            profileService.updateProfile(callerId, dto);
            UserProfileVO vo = profileService.getProfile(callerId);
            responseObserver.onNext(toLegacyProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("UpdateUserProfile(legacy) failed userId={}", callerId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 老版批量.
     */
    public void batchGetUserProfiles(BatchGetUserProfilesRequest request,
                                      StreamObserver<BatchGetUserProfilesResponse> responseObserver) {
        List<Long> ids = request.getUserIdsList();
        if (ids == null || ids.isEmpty()) {
            sendError(responseObserver, new BizException(ErrorCode.BAD_REQUEST, "userIds empty"));
            return;
        }
        if (ids.size() > 200) {
            sendError(responseObserver, new BizException(ErrorCode.BATCH_TOO_LARGE, "max 200"));
            return;
        }
        try {
            List<UserProfileVO> vos = profileService.batchGetProfile(ids, false);
            BatchGetUserProfilesResponse.Builder b = BatchGetUserProfilesResponse.newBuilder();
            for (UserProfileVO vo : vos) {
                b.addProfiles(toLegacyProto(vo));
            }
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("BatchGetUserProfiles(legacy) failed count={}", ids.size(), ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 资料编辑(新版,7 个 MVP 字段).
     */
    public void updateProfile(UpdateUserProfileRequest request,
                              StreamObserver<UserProfileProto> responseObserver) {
        Long callerId = UserGrpcService.requireCallerUserId();
        if (request.getUserId() > 0 && request.getUserId() != callerId) {
            sendError(responseObserver, new BizException(ErrorCode.FORBIDDEN, "cannot modify others"));
            return;
        }
        try {
            UpdateProfileDTO dto = UpdateProfileDTO.builder()
                    .userId(callerId)
                    .nickname(emptyToNull(request.getNickname()))
                    .age(request.getAge() == 0 ? null : request.getAge())
                    .preferredLocation(emptyToNull(request.getPreferredLocation()))
                    .bio(emptyToNull(request.getBio()))
                    .occupation(emptyToNull(request.getOccupation()))
                    .education(emptyToNull(request.getEducation()))
                    .height(request.getHeight() == 0 ? null : request.getHeight())
                    .build();
            profileService.updateProfile(callerId, dto);
            UserProfileVO vo = profileService.getProfile(callerId);
            responseObserver.onNext(toProto(vo));
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("UpdateProfile failed userId={}", callerId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * Onboarding 一次性写入完整资料.
     */
    public void upsertOnboarding(UpsertOnboardingRequest request,
                                 StreamObserver<OnboardingResponse> responseObserver) {
        Long callerId = UserGrpcService.requireCallerUserId();
        if (request.getUserId() > 0 && request.getUserId() != callerId) {
            sendError(responseObserver, new BizException(ErrorCode.FORBIDDEN, "cannot modify others"));
            return;
        }
        if (request.getGender() == Gender.GENDER_UNKNOWN) {
            sendError(responseObserver, new BizException(ErrorCode.ONBOARDING_GENDER_REQUIRED));
            return;
        }
        if (request.getBirthday() == null || request.getBirthday().isBlank()) {
            sendError(responseObserver, new BizException(ErrorCode.ONBOARDING_BIRTHDAY_REQUIRED));
            return;
        }
        try {
            OnboardingDTO dto = OnboardingDTO.builder()
                    .userId(callerId)
                    .nickname(emptyToNull(request.getNickname()))
                    .gender(request.getGender().getNumber())
                    .birthday(LocalDate.parse(request.getBirthday()))
                    .preferredLocation(emptyToNull(request.getPreferredLocation()))
                    .bio(emptyToNull(request.getBio()))
                    .occupation(emptyToNull(request.getProfession()))
                    .education(emptyToNull(request.getEducation()))
                    .height(request.getHeight() == 0 ? null : request.getHeight())
                    .interests(request.getInterestsList().stream()
                            .map(this::toInterestDTO)
                            .toList())
                    .build();
            UserProfileVO vo = profileService.upsertOnboarding(callerId, dto);
            responseObserver.onNext(OnboardingResponse.newBuilder()
                    .setProfile(toProto(vo))
                    .build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("UpsertOnboarding failed userId={}", callerId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 兴趣标签全量替换.
     */
    public void replaceUserInterests(ReplaceUserInterestsRequest request,
                                     StreamObserver<ReplaceUserInterestsResponse> responseObserver) {
        Long callerId = UserGrpcService.requireCallerUserId();
        if (request.getUserId() > 0 && request.getUserId() != callerId) {
            sendError(responseObserver, new BizException(ErrorCode.FORBIDDEN, "cannot modify others"));
            return;
        }
        List<UserInterestProto> interests = request.getInterestsList();
        if (interests == null) interests = List.of();

        long picCount = interests.stream().filter(i -> i.getPicKey() != null && !i.getPicKey().isBlank()).count();
        if (picCount > 9) {
            sendError(responseObserver, new BizException(ErrorCode.INTEREST_PIC_EXCEEDED, "max 9 pics"));
            return;
        }
        if (interests.size() > 50) {
            sendError(responseObserver, new BizException(ErrorCode.INTEREST_TAGS_EXCEEDED, "max 50 tags"));
            return;
        }

        try {
            int count = interestService.replaceUserInterests(callerId,
                    interests.stream().map(this::toInterestDTO).toList());
            responseObserver.onNext(ReplaceUserInterestsResponse.newBuilder()
                    .setCount(count)
                    .build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("ReplaceUserInterests failed userId={}", callerId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    // ===== Avatar =====

    /**
     * 头像 presign URL.
     */
    public void presignAvatarUpload(PresignAvatarUploadRequest request,
                                    StreamObserver<PresignAvatarUploadResponse> responseObserver) {
        Long callerId = UserGrpcService.requireCallerUserId();
        if (request.getUserId() > 0 && request.getUserId() != callerId) {
            sendError(responseObserver, new BizException(ErrorCode.FORBIDDEN, "cannot upload for others"));
            return;
        }
        try {
            PresignAvatarDTO dto = PresignAvatarDTO.builder()
                    .userId(callerId)
                    .ext(emptyToNull(request.getExt()))
                    .sizeBytes(request.getSizeBytes())
                    .build();
            PresignResultVO vo = avatarService.presignUpload(callerId, dto);
            responseObserver.onNext(PresignAvatarUploadResponse.newBuilder()
                    .setPresignedUrl(vo.getPresignedUrl())
                    .setObjectKey(vo.getObjectKey())
                    .setExpiresAtMs(vo.getExpiresAtMs())
                    .build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("PresignAvatarUpload failed userId={}", callerId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    /**
     * 头像上传 confirm.
     */
    public void confirmAvatarUpload(ConfirmAvatarUploadRequest request,
                                    StreamObserver<ConfirmAvatarUploadResponse> responseObserver) {
        Long callerId = UserGrpcService.requireCallerUserId();
        if (request.getUserId() > 0 && request.getUserId() != callerId) {
            sendError(responseObserver, new BizException(ErrorCode.FORBIDDEN, "cannot confirm for others"));
            return;
        }
        try {
            ConfirmAvatarDTO dto = ConfirmAvatarDTO.builder()
                    .userId(callerId)
                    .objectKey(request.getObjectKey())
                    .build();
            UserProfileVO vo = avatarService.confirmUpload(callerId, dto);
            responseObserver.onNext(ConfirmAvatarUploadResponse.newBuilder()
                    .setProfile(toProto(vo))
                    .build());
            responseObserver.onCompleted();
        } catch (BizException ex) {
            UserGrpcService.sendBizError(responseObserver, ex);
        } catch (Exception ex) {
            log.error("ConfirmAvatarUpload failed userId={}", callerId, ex);
            sendError(responseObserver, new BizException(ErrorCode.INTERNAL_ERROR, "internal error"));
        }
    }

    // ========== helpers: VO ↔ proto ==========

    private static UserProfileProto toProto(UserProfileVO vo) {
        if (vo == null) return UserProfileProto.getDefaultInstance();
        UserProfileProto.Builder b = UserProfileProto.newBuilder()
                .setUserId(vo.getUserId() == null ? 0L : vo.getUserId())
                .setNickname(vo.getNickname() == null ? "" : vo.getNickname())
                .setAge(vo.getAge() == null ? 0 : vo.getAge())
                .setGender(toGenderEnum(vo.getGender()))
                .setBirthday(vo.getBirthday() == null ? "" : vo.getBirthday().toString())
                .setPreferredLocation(vo.getPreferredLocation() == null ? "" : vo.getPreferredLocation())
                .setBio(vo.getBio() == null ? "" : vo.getBio())
                .setProfession(vo.getProfession() == null ? "" : vo.getProfession())
                .setEducation(vo.getEducation() == null ? "" : vo.getEducation())
                .setHeight(vo.getHeight() == null ? 0 : vo.getHeight())
                .setEmail(vo.getEmail() == null ? "" : vo.getEmail())
                .setRegulationStatus(vo.getRegulationStatus() == null ? 0 : vo.getRegulationStatus())
                .setPending(Boolean.TRUE.equals(vo.getPending()))
                .setLastOpenAtMs(vo.getLastOpenAtMs() == null ? 0L : vo.getLastOpenAtMs());
        if (vo.getAvatar() != null) {
            b.setAvatar(toAvatarProto(vo.getAvatar()));
        }
        if (vo.getInterests() != null) {
            for (UserInterestVO iv : vo.getInterests()) {
                b.addInterests(toInterestProto(iv));
            }
        }
        return b.build();
    }

    private static UserProfileResponse toLegacyProto(UserProfileVO vo) {
        if (vo == null) return UserProfileResponse.getDefaultInstance();
        return UserProfileResponse.newBuilder()
                .setUserId(vo.getUserId() == null ? 0L : vo.getUserId())
                .setNickname(vo.getNickname() == null ? "" : vo.getNickname())
                .setAvatarKey(vo.getAvatar() != null && vo.getAvatar().getOriginalKey() != null
                        ? vo.getAvatar().getOriginalKey() : "")
                .setBio(vo.getBio() == null ? "" : vo.getBio())
                .setAge(vo.getAge() == null ? 0 : vo.getAge())
                .setGender(vo.getGender() == null ? "" : genderString(vo.getGender()))
                .setCreatedAt(vo.getCreatedAt() == null ? "" : vo.getCreatedAt().toString())
                .build();
    }

    private static AvatarProto toAvatarProto(AvatarVO vo) {
        if (vo == null) return AvatarProto.getDefaultInstance();
        return AvatarProto.newBuilder()
                .setOriginalKey(vo.getOriginalKey() == null ? "" : vo.getOriginalKey())
                .setMinKey(vo.getMinKey() == null ? "" : vo.getMinKey())
                .setMidKey(vo.getMidKey() == null ? "" : vo.getMidKey())
                .build();
    }

    private static UserInterestProto toInterestProto(UserInterestVO vo) {
        if (vo == null) return UserInterestProto.getDefaultInstance();
        return UserInterestProto.newBuilder()
                .setTabKey(vo.getTabKey() == null ? "" : vo.getTabKey())
                .setTagKey(vo.getTagKey() == null ? "" : vo.getTagKey())
                .setPicKey(vo.getPicKey() == null ? "" : vo.getPicKey())
                .build();
    }

    private static Gender toGenderEnum(Integer v) {
        if (v == null) return Gender.GENDER_UNKNOWN;
        return switch (v) {
            case 1 -> Gender.GENDER_MALE;
            case 2 -> Gender.GENDER_FEMALE;
            default -> Gender.GENDER_UNKNOWN;
        };
    }

    private static String genderString(Integer v) {
        if (v == null) return "";
        return switch (v) {
            case 1 -> "MALE";
            case 2 -> "FEMALE";
            default -> "";
        };
    }

    private com.dating.user.dto.InterestDTO toInterestDTO(UserInterestProto p) {
        return com.dating.user.dto.InterestDTO.builder()
                .tabKey(emptyToNull(p.getTabKey()))
                .tagKey(emptyToNull(p.getTagKey()))
                .picKey(emptyToNull(p.getPicKey()))
                .build();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static void sendError(StreamObserver<?> observer, BizException ex) {
        UserGrpcService.sendBizError(observer, ex);
    }
}