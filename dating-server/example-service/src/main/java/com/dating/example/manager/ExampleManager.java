package com.dating.example.manager;

import com.dating.example.entity.ExampleEntity;
import com.dating.example.mapper.ExampleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Example Manager.
 *
 * <p>Provides data access orchestration for example entities.
 * Wraps mapper calls and handles caching if needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExampleManager {

    private final ExampleMapper exampleMapper;

    /**
     * Get entity by ID.
     *
     * @param id entity ID
     * @return entity or null if not found
     */
    public ExampleEntity getById(Long id) {
        return exampleMapper.selectById(id);
    }

    /**
     * Save new entity.
     *
     * @param entity entity to save
     */
    public void save(ExampleEntity entity) {
        exampleMapper.insert(entity);
    }

    /**
     * Update existing entity.
     *
     * @param entity entity to update
     */
    public void updateById(ExampleEntity entity) {
        exampleMapper.updateById(entity);
    }

    /**
     * Delete entity by ID (soft delete).
     *
     * @param id entity ID
     */
    public void deleteById(Long id) {
        exampleMapper.deleteById(id);
    }
}
