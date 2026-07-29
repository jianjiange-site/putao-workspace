package com.dating.post.manager;

import com.dating.post.entity.PostFanoutOutboxEntity;
import com.dating.post.mapper.PostFanoutOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostFanoutOutboxManager {

    private final PostFanoutOutboxMapper mapper;

    public void enqueue(long postId, long authorUserId, long createdAtEpoch) {
        PostFanoutOutboxEntity entity = new PostFanoutOutboxEntity();
        entity.setEventId(UUID.randomUUID().toString());
        entity.setPostId(postId);
        entity.setAuthorUserId(authorUserId);
        entity.setCreatedAtEpoch(createdAtEpoch);
        entity.setStatus("PENDING");
        entity.setAttempts(0);
        entity.setNextRetryAt(Instant.now());
        entity.setCreatedAt(Instant.now());
        mapper.insert(entity);
    }

    public List<PostFanoutOutboxEntity> listDue(int limit) {
        return mapper.selectDue(Instant.now(), limit);
    }

    public void markDelivered(long id) {
        mapper.markDelivered(id);
    }

    public void markRetry(long id, int attempts) {
        long delaySeconds = Math.min(300L, 1L << Math.min(attempts, 8));
        mapper.markRetry(id, Instant.now().plusSeconds(delaySeconds));
    }
}
