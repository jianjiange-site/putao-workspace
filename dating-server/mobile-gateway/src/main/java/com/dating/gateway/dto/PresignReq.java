package com.dating.gateway.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class PresignReq {
    private String ext = "jpg";
    private Long expectedSizeBytes;
}
