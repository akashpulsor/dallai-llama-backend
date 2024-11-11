package org.springframework.boot.crm.dto;

import jakarta.validation.constraints.Email;
import lombok.Data;

@Data
public class ValidateVerificationCodeRequestDto {

    @Email
    private String email;
    private int verificationCode;
}
