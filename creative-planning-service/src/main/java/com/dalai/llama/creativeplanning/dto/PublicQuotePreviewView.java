package com.dalai.llama.creativeplanning.dto;

/** Client-safe quote preview -- backs {@code GET
 * /v1/public/project-requirements/{shareToken}/quote-preview}. Only the total price + currency
 * are visible on this endpoint; the creator-only per-second breakdown ({@code platformCost},
 * {@code creatorMarginPercent}) lives on the authenticated creator view and does not appear
 * here even accidentally. */
public record PublicQuotePreviewView(
        int durationSeconds,
        String totalPrice,
        String currency
) {
}
