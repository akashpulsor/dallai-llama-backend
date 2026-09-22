package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

/** A client-uploaded reference clip attached to a brief -- no analysis field on purpose. The
 * script prompt reads only the free-text video_shots_intent on the requirement itself, not any
 * vision analysis of the clip (client hasn't paid yet). */
public record ProjectReferenceVideoView(
        UUID id,
        UUID projectRequirementId,
        String bucket,
        String objectKey,
        String signedUrl,
        String originalFilename,
        String contentType,
        Long sizeBytes
) {
}
