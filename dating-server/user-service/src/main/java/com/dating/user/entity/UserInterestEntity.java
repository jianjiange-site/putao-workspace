package com.dating.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * UserInterest Entity — 兴趣标签.
 *
 * <p>{@code ReplaceUserInterests} 走全量替换:事务内 DELETE + 批量 INSERT.
 */
@Data
@TableName("user_interest")
public class UserInterestEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 兴趣大类 */
    private String tabKey;

    /** 具体标签 */
    private String tagKey;

    /** 图片标签的 object_key,可空 */
    private String picKey;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
