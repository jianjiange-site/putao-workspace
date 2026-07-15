package com.dating.post.mq.consumer;

import com.dating.post.client.UserClient;
import com.dating.post.constant.RedisKey;
import com.dating.post.mq.producer.PostFanoutProducer;
import com.dating.post.mq.producer.PostFanoutProducer.FanoutMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Post Fanout Consumer.
 *
 * <p>消费写扩散消息,拉取关注者列表后写入用户时间线.
 *
 * <p>默认使用 CONCURRENTLY 模式消费.
 * user-service down 时抛异常,RocketMQ 自动重投.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "youjianxin-dating-dev-post-fanout-v1",
        consumerGroup = "youjianxin-dating-dev-post-service-fanout",
        maxReconsumeTimes = 16
)
public class PostFanoutConsumer implements RocketMQListener<FanoutMessage> {

    private static final int TIMELINE_MAX_SIZE = 100;

    private final UserClient userClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(FanoutMessage message) {
        log.debug("Received fanout message: postId={} authorUserId={}",
                message.getPostId(), message.getAuthorUserId());

        // 1. 拉取关注者列表
        List<Long> followers = userClient.getFriendUserIds(message.getAuthorUserId());
        if (followers.isEmpty()) {
            log.debug("No followers for userId={}, skip fanout", message.getAuthorUserId());
            return;
        }

        // 2. 写入每个关注者的 timeline
        for (long follower : followers) {
            try {
                writeToTimeline(follower, message);
            } catch (Exception e) {
                log.error("Failed to write to timeline: follower={} postId={}",
                        follower, message.getPostId(), e);
                throw e; // 抛异常触发重投
            }
        }

        log.info("Fanout complete: postId={} followers={}", message.getPostId(), followers.size());
    }

    private void writeToTimeline(long followerId, FanoutMessage message) {
        String key = RedisKey.userTimeline(followerId);

        // ZADD (score = epoch, member = postId)
        stringRedisTemplate.opsForZSet().add(
                key,
                String.valueOf(message.getPostId()),
                message.getCreatedAtEpoch()
        );

        // 裁剪到 100 条
        Long size = stringRedisTemplate.opsForZSet().size(key);
        if (size != null && size > TIMELINE_MAX_SIZE) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, size - TIMELINE_MAX_SIZE - 1);
        }

        // 设置 TTL 7 天
        stringRedisTemplate.expire(key, Duration.ofDays(7));
    }
}
