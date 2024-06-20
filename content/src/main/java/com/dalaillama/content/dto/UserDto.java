package com.dalaillama.content.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;


@Data
public class UserDto {
    private int userId;

    private String name;

    private String number;

    private String email;

    @JsonIgnore
    private String password;

    private String profileImage;

    private long followerCount;

    private Gender gender;

    private String bio;

}
