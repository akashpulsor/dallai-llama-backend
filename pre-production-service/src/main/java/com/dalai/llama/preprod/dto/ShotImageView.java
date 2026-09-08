package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ShotImageKind;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShotImageView(
        UUID id,
        ShotImageKind kind,
        String bucket,
        String objectKey,
        String signedUrl,
        /** Any rendered on-image text detected in THIS image (packaging copy, signage, motion-
         * graphic text). Empty/null when the image has no visible text -- the frontend uses this,
         * not a per-shot-type allowlist, to decide when text-only affordances (download, "fix on-
         * image text") apply. Populated by {@code ShotImageDescriptionService}. */
        String onScreenText,
        /** BCP-47 of {@link #onScreenText} when set (e.g. {@code en}, {@code hi}, {@code hi-Latn}
         * for romanized Hindi). */
        String onScreenTextLanguage,
        OffsetDateTime createdAt
) {
}
