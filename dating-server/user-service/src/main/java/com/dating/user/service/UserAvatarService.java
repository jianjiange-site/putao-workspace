package com.dating.user.service;

import com.dating.user.dto.ConfirmAvatarDTO;
import com.dating.user.dto.PresignAvatarDTO;
import com.dating.user.vo.PresignResultVO;
import com.dating.user.vo.UserProfileVO;

/**
 * 头像上传域服务接口 — Presigned PUT 直传模式.
 *
 * <p>对象存储能力由 dating-common 的 ObjectStorage 抽象提供,本服务只签 URL + 落库 object_key.
 * MVP 阶段,头像字段暂未在 user_info 上建 custom_avatar JSONB 列,V1 只返回 Presign
 * + Confirm 走通流程,数据持久化留给 V2 加列.
 */
public interface UserAvatarService {

    /**
     * 签 presigned PUT URL(5 min TTL).
     *
     * <p>校验 ext 白名单 / size ≤ 10MB,objectKey 模板 {@code avatar/{userId}/{uuid}.{ext}}.
     */
    PresignResultVO presignUpload(Long userId, PresignAvatarDTO dto);

    /**
     * 客户端 PUT 完成后调用 — 校验存在 + 更新 custom_avatar JSONB + 清缓存.
     */
    UserProfileVO confirmUpload(Long userId, ConfirmAvatarDTO dto);
}