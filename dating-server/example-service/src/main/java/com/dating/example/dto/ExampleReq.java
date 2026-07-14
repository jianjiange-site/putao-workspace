package com.dating.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Example Request DTO.
 *
 * <p>Request DTO for create/update example operations.
 */
@Data
public class ExampleReq {

    @NotBlank(message = "Name cannot be blank")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;
}
