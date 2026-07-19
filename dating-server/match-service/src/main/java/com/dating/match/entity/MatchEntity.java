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
 * 匹配关系实体.
 *
 * <p>主键 (user_id_low, user_id_high) UNIQUE 保证 (a,b) 与 (b,a) 视为同一.
 */
@Data
@TableName("match")
public class MatchEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** min(uid1, uid2) */
    private Long userIdLow;

    /** max(uid1, uid2) */
    private Long userIdHigh;

    private Instant matchedAt;

    /** SWIPE_MATCH / SWIPE_SUPER_HI / ... */
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
