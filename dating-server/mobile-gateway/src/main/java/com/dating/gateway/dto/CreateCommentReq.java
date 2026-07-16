package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class CreateCommentReq {
    @NotBlank(message = "Content is required")
    @Size(max = 512)
    private String content;
}
