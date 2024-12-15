package org.springframework.boot.crm.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LeadResponseDto {

    private int leadId;

    private int businessId;

    private String leadAddress;

    private String leadEmail;

    private String leadPhone;



    private String leadName;

    private String leadGender;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
