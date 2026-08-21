package com.dalai.llama.creativeplanning.dto;

import java.util.List;

/** The full "what has happened to this product" view -- every reference image (with its vision
 * analysis) and every campaign planning session (with its locked idea, if any), most recent
 * first. Closes the design doc's {@code GET /v1/products/{productId}/journey}. */
public record ProductJourneyView(
        ProductProfileView product,
        List<ProductReferenceImageView> referenceImages,
        List<CampaignSessionSummaryView> sessions
) {
}
