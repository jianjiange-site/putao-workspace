package com.dating.example.vo;

import lombok.Data;

import java.time.Instant;

/**
 * Example Value Object.
 *
 * <p>Response VO for example data.
 */
@Data
public class ExampleVO {

    private Long id;

    private String name;

    private String description;

    private Instant createdAt;

    private Instant updatedAt;
}
