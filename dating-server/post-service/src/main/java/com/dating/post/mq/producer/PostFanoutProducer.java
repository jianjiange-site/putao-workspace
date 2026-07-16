package com.dating.post.mq.producer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * Post Fanout Producer.
 *
 * <p>发帖后发送 RocketMQ 消息做写扩散.
 *
 * <p>本地 retry 3 次,timeout 2s/次.
 * 3 次全失败 -> log.error + 指标计数,不阻塞返回.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostFanoutProducer {

    /** Topic 名称 */
    public static final String TOPIC = "youjianxin-dating-dev-post-fanout-v1";

    private final RocketMQTemplate rocketMQTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * 发送写扩散消息.
     *
     * @param postId 帖子ID
     * @param authorUserId 作者用户ID
     * @param createdAtEpoch 创建时间戳(秒)
     */
    public void send(long postId, long authorUserId, long createdAtEpoch) {
        FanoutMessage message = new FanoutMessage(postId, authorUserId, createdAtEpoch);

        for (int i = 0; i < 3; i++) {
            try {
                var result = rocketMQTemplate.syncSend(TOPIC, message, 2000);
                if (SendStatus.SEND_OK == result.getSendStatus()) {
                    log.debug("Fanout message sent: postId={} attempt={}", postId, i + 1);
                    return;
                }
            } catch (Exception e) {
                log.warn("Fanout send retry, postId={} attempt={}", postId, i + 1, e);
            }
        }

        log.error("Fanout send FAILED after 3 retries, postId={}", postId);
        // 指标计数
        Counter.builder("post.fanout.produce.fail")
                .description("Fanout message send failed after 3 retries")
                .tag("topic", TOPIC)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 写扩散消息结构.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FanoutMessage {
        private long postId;
        private long authorUserId;
        private long createdAtEpoch;
    }
}
