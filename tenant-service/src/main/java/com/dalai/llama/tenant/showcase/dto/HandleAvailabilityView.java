package com.dalai.llama.tenant.showcase.dto;

/** Result of a live handle check: the normalised handle and, when it can't be used, why. */
public record HandleAvailabilityView(
        String handle,
        boolean available,
        /* Null when available; otherwise TOO_SHORT, TOO_LONG, INVALID_FORMAT, RESERVED or TAKEN. */
        String reason
) {
}
