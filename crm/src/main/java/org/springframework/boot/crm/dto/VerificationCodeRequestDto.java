package org.springframework.boot.crm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerificationCodeRequestDto {
    @Email
    @NotBlank
    @JsonProperty("email")
    private String email;
}
