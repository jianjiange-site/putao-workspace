package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Post Entity — 帖子主表.
 *
 * <p>业务主键: post_id (雪花ID)
 */
@Data
@TableName("posts")
public class PostEntity {

    /** 内部物理主键,不对外暴露 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 雪花ID,跨库稳定的业务主键 */
    private Long postId;

    /** 发帖人 */
    private Long userId;

    /** 文本内容 */
    private String content;

    /** 状态: 0=已删 / 1=正常 / 2=审核中 */
    private Integer status;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
