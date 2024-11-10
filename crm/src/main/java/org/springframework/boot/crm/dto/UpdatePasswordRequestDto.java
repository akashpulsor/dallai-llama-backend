package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class UpdatePasswordRequestDto {
    private String email;
    private String oldPassword;
    private String newPassword;
}
