package com.dating.match.manager;

import com.dating.match.entity.LikeRecordEntity;
import com.dating.match.mapper.LikeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * like_record Manager.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeRecordManager {

    private final LikeRecordMapper likeRecordMapper;

    /**
     * UPSERT like_record:同 (from, to) 二次写入更新 source + liked_at.
     */
    public void upsert(long fromUserId, long toUserId, int fromUserType, int source, String likeContent) {
        LikeRecordEntity existing = likeRecordMapper.findByPair(fromUserId, toUserId);
        if (existing != null) {
            existing.setSource(source);
            existing.setLikedAt(Instant.now());
            if (likeContent != null) {
                existing.setLikeContent(likeContent);
            }
            likeRecordMapper.updateById(existing);
            return;
        }
        LikeRecordEntity entity = new LikeRecordEntity();
        entity.setFromUserId(fromUserId);
        entity.setToUserId(toUserId);
        entity.setFromUserType(fromUserType);
        entity.setSource(source);
        entity.setLikeContent(likeContent);
        entity.setLikedAt(Instant.now());
        try {
            likeRecordMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.debug("like_record duplicate insert (race): from={} to={}", fromUserId, toUserId);
        }
    }

    /**
     * match 创建副作用:双向 like_record 软删.
     */
    public int softDeleteByPair(long a, long b) {
        return likeRecordMapper.softDeleteByPair(a, b);
    }

    /**
     * 24h DH like 上限检查.
     */
    public long countDhLikeSince(long userId, Instant since) {
        return likeRecordMapper.countFromDhSince(userId, 2, since);
    }

    /**
     * 拉取"谁 like 了我"分页(按 liked_at DESC).
     */
    public List<LikeRecordEntity> findToUser(long userId, int limit) {
        return likeRecordMapper.listToUser(userId, limit);
    }
}