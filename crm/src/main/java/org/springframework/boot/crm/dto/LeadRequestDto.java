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

    private String leadAddress;

    private String leadEmail;

    private String leadLatLocation;

    private String leadLongLocation;

    private String leadName;

    private String leadPhone;

    private String gender;


    private String leadWatsApp;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private OnBoardingDto.Address address;
}
