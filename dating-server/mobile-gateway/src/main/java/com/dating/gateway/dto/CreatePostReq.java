package com.dating.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Data
@Getter
@Setter
public class CreatePostReq {
    @NotBlank(message = "Content is required")
    @Size(max = 1024)
    private String content;

    @Size(max = 9)
    private List<String> imageKeys;
}
