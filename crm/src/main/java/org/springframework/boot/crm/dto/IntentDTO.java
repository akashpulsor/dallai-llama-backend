package org.springframework.boot.crm.dto;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IntentDTO {
    private Integer intentId;

    private String intentName;

    private Integer sequenceNumber;

    private String description;

}
