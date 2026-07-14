package com.dating.example.service;

import com.dating.example.dto.ExampleReq;
import com.dating.example.vo.ExampleVO;

/**
 * Example Service Interface.
 *
 * <p>Defines business operations for the example service.
 */
public interface ExampleService {

    /**
     * Get example by ID.
     *
     * @param id example ID
     * @return example data
     */
    ExampleVO getExample(Long id);

    /**
     * Create a new example.
     *
     * @param req example creation request
     * @return created example data
     */
    ExampleVO createExample(ExampleReq req);

    /**
     * Update an existing example.
     *
     * @param id example ID
     * @param req example update request
     * @return updated example data
     */
    ExampleVO updateExample(Long id, ExampleReq req);

    /**
     * Delete an example by ID (soft delete).
     *
     * @param id example ID
     */
    void deleteExample(Long id);
}
