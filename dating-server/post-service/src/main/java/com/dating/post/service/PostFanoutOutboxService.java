package com.dating.post.service;

import com.dating.post.entity.PostFanoutOutboxEntity;
import com.dating.post.manager.PostFanoutOutboxManager;
import com.dating.post.mq.producer.PostFanoutProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostFanoutOutboxService {

    private static final int BATCH_SIZE = 100;

    private final PostFanoutOutboxManager outboxManager;
    private final PostFanoutProducer producer;

    public int deliverDue() {
        int delivered = 0;
        for (PostFanoutOutboxEntity event : outboxManager.listDue(BATCH_SIZE)) {
            boolean sent = producer.send(
                    event.getEventId(),
                    event.getPostId(),
                    event.getAuthorUserId(),
                    event.getCreatedAtEpoch()
            );
            if (sent) {
                outboxManager.markDelivered(event.getId());
                delivered++;
            } else {
                outboxManager.markRetry(event.getId(), event.getAttempts() + 1);
                log.warn("Fanout outbox delivery scheduled for retry: eventId={} attempts={}",
                        event.getEventId(), event.getAttempts() + 1);
            }
        }
        return delivered;
    }
}
