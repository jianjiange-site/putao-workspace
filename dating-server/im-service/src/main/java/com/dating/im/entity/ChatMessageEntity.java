package com.dating.im.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 消息实体.
 *
 * <p>对应 chat_messages 表.
 */
@Data
@TableName("chat_messages")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局消息 ID */
    private String messageId;

    /** 会话 ID */
    private Long convId;

    /** 发送方用户 ID */
    private Long fromUserId;

    /** 接收方用户 ID */
    private Long toUserId;

    /** 消息内容 */
    private String content;

    /** 消息类型: 1=文本 2=图片 3=语音 4=视频 5=礼物 */
    private Integer type;

    /** 路由类型: BH_BH / BH_DH / DH_BH / DH_DH */
    private String routeType;

    /** 消息元数据(JSON) */
    private String metadata;

    /** 会话类型: SINGLE/GROUP */
    private String conversationType;

    /** IM 引擎来源: openim/tencent */
    private String provider;

    /** 发送时间戳(epoch 秒) */
    private Long timestamp;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
