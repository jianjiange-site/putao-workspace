package com.dating.gateway.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class UpdateProfileReq {
    @Size(min = 1, max = 50)
    private String nickname;
    private Integer age;
    private Integer height;
    private String bio;
    private String occupation;
    private String education;
    private String location;
}
