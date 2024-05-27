package com.dalaillama.content.dto;


import lombok.Data;

@Data
public class WordPressCredentialsRequestDto {
    private String userName;
    private String password;
    private int userId;
}
