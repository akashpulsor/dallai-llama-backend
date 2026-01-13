package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class CallAuthorizationResponse {
    private boolean authorized;
    private String state;
    private String reason;
    private BigDecimal remainingBalance;
}
