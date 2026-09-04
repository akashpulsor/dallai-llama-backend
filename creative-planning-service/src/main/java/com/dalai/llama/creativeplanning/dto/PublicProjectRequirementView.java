package com.dalai.llama.creativeplanning.dto;

import java.math.BigDecimal;
import java.util.List;

/** What an external, unauthenticated viewer (someone holding a share link, not a tenant user)
 * sees -- deliberately excludes tenant id, created-by, and any internal cross-references {@link
 * ProjectRequirementView} carries, and the platform/margin cost breakdown behind {@code
 * quotedTotalPrice} (a client sees only the final number they'd pay, not how it splits between
 * platform and creator). {@code brand}/{@code product}/{@code productReferenceImages}/{@code
 * projectReferenceImages} are the same full picture the creator saw while building this brief --
 * null/empty wherever that piece wasn't filled in, never an error. */
public record PublicProjectRequirementView(
        String briefText,
        String targetAudience,
        String campaignDirection,
        Integer durationSeconds,
        List<String> languages,
        BigDecimal quotedTotalPrice,
        String quotedCurrency,
        // The actual amount due now if the creator has configured a partial/"token" payment
        // (requiredAmount < quotedTotalPrice) -- equal to quotedTotalPrice when
        // requiredPaymentPercent is 100 (the default), so an unconfigured brief looks unchanged.
        int requiredPaymentPercent,
        BigDecimal requiredAmount,
        boolean funded,
        BrandContextView brand,
        ProductProfileView product,
        List<ProductReferenceImageView> productReferenceImages,
        List<ProjectReferenceImageView> projectReferenceImages
) {
}
