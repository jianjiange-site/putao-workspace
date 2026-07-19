package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * DH 模拟互动任务实体.
 *
 * <p>短生命周期;generator 写、executor 读+删;执行后硬删.
 */
@Data
@TableName("dh_interaction_task")
public class DhInteractionTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fromUserId;

    private Long toUserId;

    /** 1=LIKE 2=VISIT */
    private Integer action;

    /** 1=ONLINE 2=OFFLINE */
    private Integer scene;

    private Instant executeTime;

    private String likeContent;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
