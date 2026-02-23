package com.dalai.llama.tenant.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Plan information
 */
@Builder
public record PlanInfo(
        UUID id,
        String code,
        String name,
        String tier,
        BigDecimal monthlyPrice,
        BigDecimal perAgentFee,
        BigDecimal setupFee,
        Integer includedAgents,
        Integer includedMinutes,
        String aiStackType,
        BigDecimal aiRatePerMin
) {}
