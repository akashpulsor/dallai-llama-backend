package com.dalaillama.content.dto;

import lombok.Data;

@Data
public class LoginResponseDto {
    private String accessToken;

    private String refreshToken;

    private String type = "Bearer";

    private UserInfoResponse userInfoResponse;
}
