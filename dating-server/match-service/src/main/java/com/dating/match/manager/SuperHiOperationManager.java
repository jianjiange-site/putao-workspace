package com.dating.match.manager;

import com.dating.match.entity.SuperHiOperationEntity;
import com.dating.match.mapper.SuperHiOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SuperHiOperationManager {
    private final SuperHiOperationMapper mapper;

    public SuperHiOperationEntity findByOperationKey(String operationKey) {
        return mapper.findByOperationKey(operationKey);
    }

    public SuperHiOperationEntity insertOrGet(SuperHiOperationEntity entity) {
        try {
            mapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException ignored) {
            return mapper.findByOperationKey(entity.getOperationKey());
        }
    }

    public void updateProgress(Long id, String status, int coinsUsed, String lastError) {
        mapper.updateProgress(id, status, coinsUsed, truncate(lastError));
    }

    public void markCompleted(Long id, Long matchId) {
        mapper.markCompleted(id, matchId);
    }

    public List<SuperHiOperationEntity> listRecoverable(Instant before, int limit) {
        return mapper.listRecoverable(before, limit);
    }

    public void hardDelete(Long id) {
        mapper.hardDelete(id);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
