package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Post Mapper.
 *
 * <p>MyBatis-Plus mapper for posts table.
 */
@Mapper
public interface PostMapper extends BaseMapper<PostEntity> {

    /**
     * 用户帖子列表分页查询(游标).
     *
     * @param userId 用户ID
     * @param cursor 游标(上一页最末的post_id)
     * @param pageSize 每页大小
     * @return 帖子列表
     */
    @Select("SELECT * FROM posts " +
            "WHERE user_id = #{userId} AND deleted = 0 AND status = 1 AND post_id &lt; #{cursor} " +
            "ORDER BY post_id DESC LIMIT #{pageSize}")
    List<PostEntity> selectByUserIdWithCursor(@Param("userId") Long userId,
                                              @Param("cursor") Long cursor,
                                              @Param("pageSize") int pageSize);

    /**
     * 近3天帖子查询(Feed池重建用).
     *
     * @return 帖子列表(仅含post_id, user_id, created_at)
     */
    @Select("SELECT post_id, user_id, created_at FROM posts " +
            "WHERE deleted = 0 AND status = 1 AND created_at >= NOW() - INTERVAL '3 days'")
    List<PostEntity> selectRecentPosts();

}
