package com.dating.match.manager;

import com.dating.match.entity.VisitRecordEntity;
import com.dating.match.mapper.VisitRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * visit_record Manager.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitRecordManager {

    private final VisitRecordMapper visitRecordMapper;

    /**
     * 根据 (from, to) 查询.
     */
    public VisitRecordEntity findByPair(long from, long to) {
        return visitRecordMapper.findByPair(from, to);
    }

    /**
     * UPSERT 落 visit_record(visit_count + 1).
     *
     * <p>同事务由调用方负责.
     */
    public void upsert(long fromUserId,
                       int fromUserType,
                       long toUserId,
                       int source) {
        VisitRecordEntity existing = visitRecordMapper.findByPair(fromUserId, toUserId);
        if (existing != null) {
            existing.setVisitCount(existing.getVisitCount() + 1);
            existing.setVisitedAt(Instant.now());
            existing.setSource(source);
            visitRecordMapper.updateById(existing);
            return;
        }
        VisitRecordEntity entity = new VisitRecordEntity();
        entity.setFromUserId(fromUserId);
        entity.setToUserId(toUserId);
        entity.setFromUserType(fromUserType);
        entity.setSource(source);
        entity.setVisitCount(1);
        entity.setVisitedAt(Instant.now());
        try {
            visitRecordMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发条件下被另一线程写 — 不重试避免双重 +1
            log.debug("visit_record duplicate insert (race): from={} to={}", fromUserId, toUserId);
        }
    }

    /**
     * 拉取 to_user_id 的 visit 列表.
     */
    public List<VisitRecordEntity> listToUser(long userId, int limit) {
        return visitRecordMapper.listToUser(userId, limit);
    }

    /**
     * 24h DH visit 上限检查.
     */
    public long countDhVisitSince(long userId, Instant since) {
        return visitRecordMapper.countFromDhSince(userId, 2, since);
    }
}
