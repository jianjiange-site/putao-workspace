package com.dating.gateway.service.impl;

import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    @Value("${minio.endpoint:https://minio-api.jianjiange.site}")
    private String minioEndpoint;

    @Value("${minio.bucket:putao}")
    private String bucket;

    @Override
    public String getPresignedUploadUrl(Long userId, String ext, Long expectedSizeBytes) {
        String objectKey = String.format("avatar/%d/%s/%s.%s",
                userId,
                java.time.YearMonth.now().toString(),
                UUID.randomUUID(),
                ext != null ? ext : "jpg");

        String presignedUrl = minioEndpoint + "/" + bucket + "/" + objectKey + "?presigned=true";
        log.info("Generated presigned URL for user {}: {}", userId, objectKey);
        return presignedUrl;
    }

    @Override
    public String confirmUpload(Long userId, String objectKey) {
        log.info("User {} confirmed upload: {}", userId, objectKey);
        return objectKey;
    }
}
