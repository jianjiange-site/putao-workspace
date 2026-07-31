package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** Match side-effect outbox with a stable event key and worker lease. */
@Data
@TableName("match_outbox")
public class MatchOutboxEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long matchId;
    private String eventKey;
    private String action;
    private String payloadJson;
    private Integer attempts;
    private Instant nextRetryAt;
    private String status;
    private String lockedBy;
    private Instant lockedUntil;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
    @TableLogic
    private Boolean deleted;
}
