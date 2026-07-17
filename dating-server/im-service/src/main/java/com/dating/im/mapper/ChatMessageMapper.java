package com.dating.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.im.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息 Mapper.
 *
 * <p>操作 chat_messages 表.
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
}
