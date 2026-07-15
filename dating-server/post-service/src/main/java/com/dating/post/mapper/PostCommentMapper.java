package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostCommentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * PostComment Mapper.
 *
 * <p>MyBatis-Plus mapper for post_comments table.
 */
@Mapper
public interface PostCommentMapper extends BaseMapper<PostCommentEntity> {

}
