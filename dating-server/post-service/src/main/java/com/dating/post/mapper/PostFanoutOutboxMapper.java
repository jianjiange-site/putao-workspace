package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostFanoutOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PostFanoutOutboxMapper extends BaseMapper<PostFanoutOutboxEntity> {

    @Select("SELECT * FROM post_fanout_outbox " +
            "WHERE status = 'PENDING' AND next_retry_at <= #{now} " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<PostFanoutOutboxEntity> selectDue(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("UPDATE post_fanout_outbox SET status='DELIVERED', delivered_at=NOW() " +
            "WHERE id=#{id} AND status='PENDING'")
    int markDelivered(@Param("id") Long id);

    @Update("UPDATE post_fanout_outbox SET attempts=attempts+1, next_retry_at=#{nextRetryAt} " +
            "WHERE id=#{id} AND status='PENDING'")
    int markRetry(@Param("id") Long id, @Param("nextRetryAt") Instant nextRetryAt);
}
