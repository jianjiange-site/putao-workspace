package com.dating.example.controller;

import com.dating.example.service.ExampleService;
import com.dating.example.vo.ExampleVO;
import com.dating.example.dto.ExampleReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Example REST Controller.
 *
 * <p>Provides HTTP endpoints for the example service.
 * All endpoints follow the pattern: /api/v1/example/**
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/example")
@RequiredArgsConstructor
public class ExampleController {

    private final ExampleService exampleService;

    /**
     * Health check endpoint.
     *
     * @return simple health status
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    /**
     * Get example by ID.
     *
     * @param id example ID
     * @return example data
     */
    @GetMapping("/{id}")
    public ExampleVO getExample(@PathVariable Long id) {
        log.debug("Getting example by id: {}", id);
        return exampleService.getExample(id);
    }

    /**
     * Create a new example.
     *
     * @param req example creation request
     * @return created example data
     */
    @PostMapping
    public ExampleVO createExample(@RequestBody @Validated ExampleReq req) {
        log.debug("Creating example: {}", req);
        return exampleService.createExample(req);
    }

    /**
     * Update an existing example.
     *
     * @param id example ID
     * @param req example update request
     * @return updated example data
     */
    @PutMapping("/{id}")
    public ExampleVO updateExample(@PathVariable Long id, @RequestBody @Validated ExampleReq req) {
        log.debug("Updating example id: {}, req: {}", id, req);
        return exampleService.updateExample(id, req);
    }

    /**
     * Delete an example by ID (soft delete).
     *
     * @param id example ID
     */
    @DeleteMapping("/{id}")
    public void deleteExample(@PathVariable Long id) {
        log.debug("Deleting example id: {}", id);
        exampleService.deleteExample(id);
    }
}
