package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * match 副作用发件箱.
 *
 * <p>IM 建会话 / 系统消息 / DH 开场白失败时入箱,后台 retry.
 */
@Data
@TableName("match_outbox")
public class MatchOutboxEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long matchId;

    /** ENSURE_CONVERSATION / SYSTEM_MSG / DH_OPENING */
    private String action;

    /** JSON 序列化 payload */
    private String payloadJson;

    private Integer attempts;

    private Instant nextRetryAt;

    /** PENDING / DONE / DEAD */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
