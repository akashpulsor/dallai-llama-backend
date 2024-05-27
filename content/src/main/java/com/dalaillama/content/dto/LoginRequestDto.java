package com.dalaillama.content.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LoginRequestDto {

    private String userName;
    private String password;
}
