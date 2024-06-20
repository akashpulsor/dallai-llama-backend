package com.dalaillama.content.dto;

import com.dalaillama.content.entity.Role;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class SignUpRequestDto {

    @NotBlank
    @Size(max = 50)
    @Email
    private String email;


    @NotBlank
    @Size(max = 13)
    private String phone;

    @NotBlank
    @Size(min = 6, max = 40)
    private String password;

    Set<String> roles = new HashSet<>();

    @Size(min = 3, max = 20)
    private String name;

    private String companyName;

    private String linkedInUrl;

    private String githubUrl;

    private String number;

    private String profileImage;

    private long followerCount;

    private String bio;

    private Gender gender;

}
