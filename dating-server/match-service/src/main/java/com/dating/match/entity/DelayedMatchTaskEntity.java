package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** Durable 15s-2min DH delayed match task. */
@Data
@TableName("delayed_match_task")
public class DelayedMatchTaskEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long dhUserId;
    private String source;
    private Instant executeAt;
    private Integer attempts;
    private Instant nextRetryAt;
    private String status;
    private String lockedBy;
    private Instant lockedUntil;
    private String lastError;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
    @TableLogic
    private Boolean deleted;
}
