package org.springframework.boot.crm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BillingResponse {
    private int callLogId;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private double totalCharges;
}
