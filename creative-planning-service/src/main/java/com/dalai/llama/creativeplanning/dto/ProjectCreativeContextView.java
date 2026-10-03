package com.dalai.llama.creativeplanning.dto;

import java.util.List;
import java.util.UUID;

/**
 * Everything the creative side knows about a pre-production project, for pre-production-service's
 * Creative Direction stage: the locked idea, the brief it came from, and the client's reference
 * images and videos with whatever analysis exists. {@code brief} is null for a project that came
 * from a campaign-planning chat rather than a brief; {@code references} is then empty.
 */
public record ProjectCreativeContextView(
        UUID lockedIdeaId,
        Idea idea,
        Brief brief,
        List<ReferenceAsset> references
) {

    public record Idea(String title, String concept, String targetAudience, String campaignAngle,
                       String keyMessage, String tone) {}

    public record Brief(UUID requirementId, String briefText, String targetAudience, String campaignDirection,
                        Integer durationSeconds) {}

    /** One client-supplied reference, identified by its own row id in project_reference_image /
     * project_reference_video. {@code clientInstruction} is what the client said about it, if
     * anything; {@code analysis} the stored vision analysis (images only today), else null. */
    public record ReferenceAsset(UUID assetId, ReferenceMediaType mediaType, String bucket, String objectKey,
                                 String signedUrl, String contentType, String originalFilename,
                                 String clientInstruction, String analysis) {}

    public enum ReferenceMediaType { IMAGE, VIDEO }
}
