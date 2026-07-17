package com.dating.im.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户在线会话实体.
 *
 * <p>记录用户上线/下线历史.
 */
@Data
@TableName("user_online_session")
public class UserOnlineSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 真人用户 ID(数字人不在此表) */
    private Long userId;

    /** 登录平台: 1=iOS 2=Android 3=Web */
    private Integer platform;

    /** 上线时刻 */
    private OffsetDateTime onlineAt;

    /** 下线时刻,NULL 表示还在线 */
    private OffsetDateTime offlineAt;

    /** 在线时长(秒) */
    private Integer durationSeconds;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
