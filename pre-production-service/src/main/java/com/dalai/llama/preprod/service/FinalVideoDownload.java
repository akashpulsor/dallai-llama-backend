package com.dalai.llama.preprod.service;

import java.time.OffsetDateTime;

/**
 * When the client may download their film: once they have paid for it, or when the creator has
 * opened downloads for them by hand. Every way a package gets locked is a payment (a verified
 * Razorpay charge, the capture webhook, or a brief already paid in full), so a lock stamp is the
 * payment. Before that the film can be watched on the review page but not saved.
 */
final class FinalVideoDownload {

    private FinalVideoDownload() {}

    /** The film's URL to download from, or null while the client may only watch it. */
    static String url(OffsetDateTime clientLockedAt, boolean creatorUnlocked, String videoUrl) {
        return clientLockedAt != null || creatorUnlocked ? videoUrl : null;
    }
}
