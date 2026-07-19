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
 * 划卡历史实体.
 *
 * <p>对应 user_swipe_history 表 — 权威已 swipe 记录,Redis SET 是缓存.
 */
@Data
@TableName("user_swipe_history")
public class UserSwipeHistoryEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务主键:用户 ID */
    private Long userId;

    /** 目标用户 ID */
    private Long targetUserId;

    /** 目标用户类型 1=BH 2=DH */
    private Integer targetUserType;

    /** 1=LEFT 2=RIGHT 3=SUPER_HI */
    private Integer direction;

    private Instant swipedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
