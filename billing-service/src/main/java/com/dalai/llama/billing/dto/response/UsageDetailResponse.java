package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class UsageDetailResponse {
    private String metric;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal totalCost;
    private int recordCount;
}
