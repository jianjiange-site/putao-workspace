package com.dating.gateway.service;

import com.dating.gateway.vo.AvatarVO;
import com.dating.gateway.vo.PresignAvatarUploadVO;

/** Upload Service. */
public interface UploadService {
    PresignAvatarUploadVO presign(Long userId, com.dating.gateway.dto.PresignReq req);
    AvatarVO confirm(Long userId, com.dating.gateway.dto.ConfirmUploadReq req);
}
