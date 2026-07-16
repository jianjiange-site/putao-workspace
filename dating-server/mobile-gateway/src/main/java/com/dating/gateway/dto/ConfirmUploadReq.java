package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class ConfirmUploadReq {
    @NotBlank(message = "Object key is required")
    private String objectKey;
}
