package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class RefreshTokenReq {
    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
