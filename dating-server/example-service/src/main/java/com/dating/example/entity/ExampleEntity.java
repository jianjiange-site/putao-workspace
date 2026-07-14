package com.dating.example.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Example Entity.
 *
 * <p>Database entity mapped to the example table.
 */
@Data
@TableName("examples")
public class ExampleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Integer deleted;
}
