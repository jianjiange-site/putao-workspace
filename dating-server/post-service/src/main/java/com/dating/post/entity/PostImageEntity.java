package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * PostImage Entity — 帖子图片表.
 *
 * <p>联合主键: (post_id, sort_order)
 */
@Data
@TableName("post_images")
public class PostImageEntity {

    /** 业务主键引用 */
    private Long postId;

    /** 排序序号 0..8 */
    @TableId(type = IdType.INPUT)
    private Integer sortOrder;

    /** 对象存储key */
    private String imageKey;

    /** 创建时间 */
    private Instant createdAt;
}
