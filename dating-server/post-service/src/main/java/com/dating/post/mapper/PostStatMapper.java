package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostStatEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PostStatMapper extends BaseMapper<PostStatEntity> {

    @Update("UPDATE post_stats " +
            "SET like_count = GREATEST(0, like_count + #{delta}), updated_at = NOW() " +
            "WHERE post_id = #{postId}")
    int incrementLikeCount(@Param("postId") Long postId,
                           @Param("delta") int delta);

    @Update("UPDATE post_stats " +
            "SET comment_count = GREATEST(0, comment_count + #{delta}), updated_at = NOW() " +
            "WHERE post_id = #{postId}")
    int incrementCommentCount(@Param("postId") Long postId,
                              @Param("delta") int delta);
}
