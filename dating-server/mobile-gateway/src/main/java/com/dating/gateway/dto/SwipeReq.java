package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class SwipeReq {
    @NotNull(message = "Target user ID is required")
    private Long targetUserId;

    @NotBlank(message = "Direction is required")
    private String direction;
}
