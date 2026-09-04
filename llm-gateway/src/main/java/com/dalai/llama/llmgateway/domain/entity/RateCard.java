package com.dalai.llama.llmgateway.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Append-only: rows are never updated. A price change is a new row with a later
 * effectiveFrom -- cost computation always resolves the row active at the request's
 * created_at, so historical bills stay reproducible even after a price change.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rate_card")
public class RateCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rate_card_id")
    private Long rateCardId;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "input_token_cost", nullable = false, precision = 18, scale = 10)
    private BigDecimal inputTokenCost;

    @Column(name = "output_token_cost", nullable = false, precision = 18, scale = 10)
    private BigDecimal outputTokenCost;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    /** Per-second cost for duration-priced models (video) -- null for token-priced models, which
     * keep the input/output-token path. See {@code LlmGatewayService.computeCost}. */
    @Column(name = "per_second_cost", precision = 18, scale = 10)
    private BigDecimal perSecondCost;
}
