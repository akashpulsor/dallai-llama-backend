package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The "how much has this brief actually been funded" read model creative-planning-service's
 * project page needs -- a view over {@code payments WHERE project_requirement_id = ?}, not a
 * separate ledger. {@code totalFunded} only ever sums SUCCESS payments, so it and the wallet
 * balance stay consistent by construction: both are derived from the same payment rows. */
@Getter
@Builder
public class ProjectRequirementFundingView {
    private UUID projectRequirementId;
    private BigDecimal totalFunded;
    private String currency;
    private List<PaymentResponse> payments;
}
