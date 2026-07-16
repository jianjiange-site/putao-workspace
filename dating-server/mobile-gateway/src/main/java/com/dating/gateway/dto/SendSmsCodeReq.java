package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class SendSmsCodeReq {
    @NotBlank(message = "Phone number is required")
    private String phone;
}
