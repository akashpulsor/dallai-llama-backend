package com.dalai.llama.postprod.service;

import java.math.BigDecimal;
import java.util.UUID;

public record VoiceCloneResult(
        UUID llmGatewayJobId,
        String providerId,
        String providerVoiceId,
        BigDecimal actualCost
) {
}
