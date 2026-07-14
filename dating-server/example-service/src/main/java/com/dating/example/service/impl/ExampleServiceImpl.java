package com.dating.example.service.impl;

import com.dating.example.dto.ExampleReq;
import com.dating.example.entity.ExampleEntity;
import com.dating.example.exception.ExampleNotFoundException;
import com.dating.example.manager.ExampleManager;
import com.dating.example.service.ExampleService;
import com.dating.example.vo.ExampleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Example Service Implementation.
 *
 * <p>Implements business operations for the example service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExampleServiceImpl implements ExampleService {

    private final ExampleManager exampleManager;

    @Override
    public ExampleVO getExample(Long id) {
        // 1. Query from database via manager
        ExampleEntity entity = exampleManager.getById(id);

        // 2. Throw exception if not found
        if (entity == null) {
            throw new ExampleNotFoundException(id);
        }

        // 3. Convert entity to VO
        return convertToVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExampleVO createExample(ExampleReq req) {
        // 1. Convert request to entity
        ExampleEntity entity = new ExampleEntity();
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());

        // 2. Save to database
        exampleManager.save(entity);

        // 3. Return converted VO
        return convertToVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExampleVO updateExample(Long id, ExampleReq req) {
        // 1. Get existing entity
        ExampleEntity entity = exampleManager.getById(id);
        if (entity == null) {
            throw new ExampleNotFoundException(id);
        }

        // 2. Update fields
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());

        // 3. Save to database
        exampleManager.updateById(entity);

        // 4. Return converted VO
        return convertToVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteExample(Long id) {
        // 1. Verify existence
        ExampleEntity entity = exampleManager.getById(id);
        if (entity == null) {
            throw new ExampleNotFoundException(id);
        }

        // 2. Soft delete via manager
        exampleManager.deleteById(id);
    }

    /**
     * Convert entity to VO.
     *
     * @param entity source entity
     * @return target VO
     */
    private ExampleVO convertToVO(ExampleEntity entity) {
        ExampleVO vo = new ExampleVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
