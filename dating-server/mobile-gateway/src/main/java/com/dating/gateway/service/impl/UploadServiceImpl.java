package com.dating.gateway.service.impl;

import com.dating.gateway.dto.ConfirmUploadReq;
import com.dating.gateway.dto.PresignReq;
import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.UploadService;
import com.dating.gateway.vo.AvatarVO;
import com.dating.gateway.vo.PresignAvatarUploadVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UploadServiceImpl implements UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadServiceImpl.class);

    @Value("${minio.endpoint:https://minio-api.jianjiange.site}")
    private String minioEndpoint;

    @Value("${minio.bucket:putao}")
    private String bucket;

    @Override
    public PresignAvatarUploadVO presign(Long userId, PresignReq req) {
        String objectKey = String.format("avatar/%d/%s/%s.%s",
                userId,
                java.time.YearMonth.now().toString(),
                UUID.randomUUID(),
                req.getExt() != null ? req.getExt() : "jpg");

        String presignedUrl = minioEndpoint + "/" + bucket + "/" + objectKey + "?presigned=true";
        log.info("Generated presigned URL for user {}: {}", userId, objectKey);
        
        PresignAvatarUploadVO vo = new PresignAvatarUploadVO();
        vo.setUploadUrl(presignedUrl);
        vo.setObjectKey(objectKey);
        return vo;
    }

    @Override
    public AvatarVO confirm(Long userId, ConfirmUploadReq req) {
        log.info("User {} confirmed upload: {}", userId, req.getObjectKey());
        
        AvatarVO vo = new AvatarVO();
        vo.setOriginalKey(req.getObjectKey());
        return vo;
    }
}
