package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.TenantType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Entry point B: no brand exercise, no locked idea -- a user fills a brief directly (the "how
 * without a branding exercise user can start a project" path). Priced by duration rather than a
 * LEAN/STANDARD/PREMIUM tier: {@code durationSeconds} is quoted against billing-service's
 * per-second platform rate plus this creator's own margin (see {@code BillingServiceClient},
 * {@code ProjectRequirementService#createStandalone}). {@code languages} is the set of spoken
 * languages the finished video should be produced/dubbed in -- at least one is required.
 * {@code brandId} picks one of the tenant's (possibly several, see {@code BrandContextService})
 * existing brands; {@code brandContext} alone (no {@code brandId}) creates a brand-new brand, and
 * given alongside {@code brandId} it edits that existing brand in place instead. A solo creator
 * with no brand yet may give neither. {@code productDetails} is likewise optional -- when present
 * it creates a {@code ProductProfile} tagged with this requirement's id (requires a brand,
 * resolved from {@code brandId}/{@code brandContext} above); its images ride along as the {@code
 * productImages} multipart part, distinct from {@code projectReferenceImages} (what the client has
 * in mind, not the product itself -- see {@code ProjectReferenceImage}). Both image sets are
 * stored as-is at creation time; the actual vision analysis is deferred until the requirement is
 * funded (see {@code ReferenceMaterialAnalysisService}). {@code pricing} prices the brief at
 * creation (a brand's request, ready to pay at once); absent, the brief starts unpriced and the
 * creator sets the price later. */
public record CreateStandaloneRequirementRequest(
        @NotBlank String briefText,
        String targetAudience,
        String campaignDirection,
        @NotNull @Positive Integer durationSeconds,
        @NotEmpty List<@NotBlank String> languages,
        @NotNull TenantType tenantType,
        UUID brandId,
        @Valid CreateBrandContextRequest brandContext,
        @Valid CreateProductRequest productDetails,
        @Valid Pricing pricing
) {
    /** {@code pricePerSecond} given: that rate times the length (e.g. the platform's rate), and the
     * brief re-prices when the length changes. Absent: the creator's own rate for the length, from
     * billing. Either way the client pays {@code requiredPaymentPercent} of it upfront. */
    public record Pricing(
            @Positive BigDecimal pricePerSecond,
            @NotNull @Min(1) @Max(100) Integer requiredPaymentPercent
    ) {
    }
}
