package com.dating.gateway.service;

import com.dating.gateway.dto.ConfirmUploadReq;
import com.dating.gateway.dto.PresignReq;
import com.dating.gateway.vo.AvatarVO;
import com.dating.gateway.vo.PresignAvatarUploadVO;

/** Upload Service. */
public interface UploadService {
    PresignAvatarUploadVO presign(Long userId, PresignReq req);
    AvatarVO confirm(Long userId, ConfirmUploadReq req);
}
