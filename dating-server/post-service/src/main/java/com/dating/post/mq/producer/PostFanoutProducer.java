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
 * 好友写扩散 Producer.
 *
 * <p>投递结果返回给 Outbox，由 Outbox 决定 DELIVERED 或重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostFanoutProducer {

    public static final String TOPIC = "youjianxin-dating-dev-post-fanout-v1";

    private final RocketMQTemplate rocketMQTemplate;
    private final MeterRegistry meterRegistry;

    public boolean send(String eventId, long postId,
                        long authorUserId, long createdAtEpoch) {
        FanoutMessage message = new FanoutMessage(
                eventId, postId, authorUserId, createdAtEpoch);

        for (int i = 0; i < 3; i++) {
            try {
                var result = rocketMQTemplate.syncSend(TOPIC, message, 2_000);
                if (SendStatus.SEND_OK == result.getSendStatus()) {
                    log.debug("Fanout message sent: eventId={} postId={} attempt={}",
                            eventId, postId, i + 1);
                    return true;
                }
            } catch (Exception e) {
                log.warn("Fanout send retry: eventId={} postId={} attempt={}",
                        eventId, postId, i + 1, e);
            }
        }

        Counter.builder("post.fanout.produce.fail")
                .description("Fanout message send failed after local retries")
                .tag("topic", TOPIC)
                .register(meterRegistry)
                .increment();
        return false;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FanoutMessage {
        private String eventId;
        private long postId;
        private long authorUserId;
        private long createdAtEpoch;
    }
}
