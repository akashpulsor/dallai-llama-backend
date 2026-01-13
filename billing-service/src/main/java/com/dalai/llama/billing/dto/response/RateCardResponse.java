package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class RateCardResponse {
    private UUID id;
    private String metric;
    private String destinationPrefix;
    private String destinationType;
    private BigDecimal ratePerUnit;
    private String unit;
    private int billingIncrement;
    private int minimumCharge;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private int priority;
}
