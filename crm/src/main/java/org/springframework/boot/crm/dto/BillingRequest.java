package org.springframework.boot.crm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BillingRequest {
    private int callLogId;
    private int input_token;
    private int output_token;
    private int totalToken;
    private double totalCharges;
}
