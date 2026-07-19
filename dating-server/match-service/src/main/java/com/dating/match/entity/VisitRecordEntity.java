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
 * Visit 记录实体.
 *
 * <p>UNIQUE(from, to) UPSERT 累加 visit_count.
 */
@Data
@TableName("visit_record")
public class VisitRecordEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fromUserId;

    private Long toUserId;

    /** 1=BH 2=DH */
    private Integer fromUserType;

    /** 1=PROFILE_VIEW 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE */
    private Integer source;

    private Integer visitCount;

    private Instant visitedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
