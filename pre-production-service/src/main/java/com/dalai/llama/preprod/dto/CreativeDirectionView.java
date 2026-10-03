package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.ReferenceMediaType;
import com.dalai.llama.preprod.domain.ReviewActor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One director's treatment as the creator and the client see it. {@code recommended} is the AI's
 * advisory pick and never implies approval -- {@code reviewStatus} alone says what a person decided. */
public record CreativeDirectionView(
        UUID id,
        UUID generationId,
        int optionNumber,
        int version,
        UUID revisedFromId,
        String title,
        String creativeConcept,
        String directorsTreatment,
        String storytellingStyle,
        VisualLanguage visualLanguage,
        String cinematographyPhilosophy,
        String emotionalJourney,
        String soundDirection,
        String signatureCreativeDevice,
        String creativeRationale,
        boolean recommended,
        String recommendationReason,
        CreativeDirectionReviewStatus reviewStatus,
        OffsetDateTime approvedAt,
        ReviewActor approvedVia,
        List<Reference> references,
        List<Feedback> feedback,
        OffsetDateTime createdAt
) {

    public record VisualLanguage(String storyPeriod, String colorTreatment, String contrast, String texture,
                                 String overallAesthetic) {}

    /** A client reference by its original asset id. {@code url} is a fresh signed URL from
     * creative-planning-service, or null when the media cannot be reached right now -- the UI then
     * says so rather than showing anything in its place. */
    public record Reference(UUID assetId, ReferenceMediaType mediaType, String url, String contentType,
                            String originalFilename, String clientInstruction, String referenceAnalysis) {}

    public record Feedback(UUID id, ReviewActor source, String feedback, OffsetDateTime createdAt) {}
}
