package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

@Data
public class LeadRequestDto {

    private int leadId;

    private int businessId;

    private String email;

    private String name;

    private String phone;

    private String phoneCountryCode;

    private String whatsapp;

    private String whatsappCountryCode;

    private String gender;

    private boolean test;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private OnBoardingDto.Address address;
}
