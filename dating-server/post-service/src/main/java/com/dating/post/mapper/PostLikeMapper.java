package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostLikeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * PostLike Mapper.
 *
 * <p>MyBatis-Plus mapper for post_likes table.
 */
@Mapper
public interface PostLikeMapper extends BaseMapper<PostLikeEntity> {

    /**
     * 点赞幂等 upsert.
     *
     * @param userId 用户ID
     * @param postId 帖子ID
     * @param status 状态(1=已赞, 0=已取消)
     * @return 影响行数
     */
    @Insert("INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at) " +
            "VALUES (#{userId}, #{postId}, #{status}, NOW(), NOW()) " +
            "ON CONFLICT (user_id, post_id) " +
            "DO UPDATE SET status = EXCLUDED.status, updated_at = NOW() " +
            "WHERE post_likes.status <> EXCLUDED.status")
    int upsertLike(@Param("userId") Long userId,
                   @Param("postId") Long postId,
                   @Param("status") Integer status);

}
