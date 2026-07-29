package com.dating.post.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostFanoutOutboxCleanupMapper {

    @Delete("WITH expired AS (" +
            "SELECT id FROM post_fanout_outbox " +
            "WHERE status='DELIVERED' AND delivered_at < NOW() - INTERVAL '7 days' " +
            "ORDER BY id ASC LIMIT #{limit}" +
            ") DELETE FROM post_fanout_outbox WHERE id IN (SELECT id FROM expired)")
    int deleteDeliveredBatch(@Param("limit") int limit);
}
