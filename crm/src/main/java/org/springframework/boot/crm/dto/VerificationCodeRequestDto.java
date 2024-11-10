package org.springframework.boot.crm.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import lombok.Data;

@Data
public class VerificationCodeRequestDto {
    @Email
    private String email;
}
