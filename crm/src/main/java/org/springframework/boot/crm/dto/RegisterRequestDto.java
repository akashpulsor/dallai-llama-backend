package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class RegisterRequestDto {
    private String email;
    private String mobile;
    private String whatsAppNumber;
    private String businessName;
    private int countryId;
    private String password;

    Set<String> roles = new HashSet<>();
}
