package org.springframework.boot.crm.dto;

import lombok.Data;

@Data
public class RegisterResponseDto {
    private int businessId;
    private LoginResponseDto loginResponseDto;
}
