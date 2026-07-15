package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * PostStat Entity — 帖子计数底座.
 *
 * <p>只存储"已刷盘"部分. 实时值 = 底座 + Redis增量
 */
@Data
@TableName("post_stats")
public class PostStatEntity {

    /** 业务主键 */
    @TableId(type = IdType.INPUT)
    private Long postId;

    /** 累计点赞数(已刷盘部分) */
    private Integer likeCount;

    /** 累计评论数 */
    private Integer commentCount;

    /** 更新时间 */
    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
