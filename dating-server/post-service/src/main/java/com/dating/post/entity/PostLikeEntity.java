package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * PostLike Entity — 点赞幂等记录.
 *
 * <p>联合主键: (user_id, post_id)
 * 故意不DELETE,而是UPDATE status = 0再次点赞时复用同一行
 */
@Data
@TableName("post_likes")
public class PostLikeEntity {

    /** 用户ID */
    private Long userId;

    /** 帖子ID */
    private Long postId;

    /** 点赞状态: 1=已赞 / 0=已取消 */
    private Integer status;

    /** 创建时间 */
    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private Instant createdAt;

    /** 更新时间 */
    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
