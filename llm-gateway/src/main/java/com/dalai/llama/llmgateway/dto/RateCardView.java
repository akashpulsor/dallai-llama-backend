package com.dalai.llama.llmgateway.dto;

import java.math.BigDecimal;

/** One currently-effective rate of a model, as recorded -- raw, in the rate's own currency. */
public record RateCardView(
        String modelId,
        /** Render tier (e.g. "480p"); null for a tier-less rate. */
        String resolution,
        BigDecimal perSecondCost,
        BigDecimal inputTokenCost,
        BigDecimal outputTokenCost,
        String currency
) {
}
