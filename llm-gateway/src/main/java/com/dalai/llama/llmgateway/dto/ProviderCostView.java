package com.dalai.llama.llmgateway.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Raw provider spend for one project x provider x model -- what each call cost at the rate card
 * when it ran, in USD. No pricing or margin (billing-service owns that). {@code projectId} is null
 * for calls not tied to a project. */
public record ProviderCostView(UUID projectId, String providerId, String modelId, long calls, long noResult,
                               BigDecimal costUsd, Instant firstAt, Instant lastAt) {
}
