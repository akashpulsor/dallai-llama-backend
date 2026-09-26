package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Wire shape for one uploaded multi-image reference on a shot -- feeds the shot page's
 * uploaded-images gallery, the storyboard tile, the PDF exporter, and (via the video-gen
 * prompt hook) the actual video generation prompt as a captioned reference image. */
public record ShotReferenceImageView(
        UUID id,
        UUID shotId,
        String bucket,
        String objectKey,
        /** Signed URL for direct display in the browser -- rewritten on every read (short TTL).
         * Nullable when the presign fails; the UI degrades to no thumbnail rather than crashing. */
        String signedUrl,
        String contentType,
        String caption,
        Integer ordinal,
        OffsetDateTime createdAt
) {
}
