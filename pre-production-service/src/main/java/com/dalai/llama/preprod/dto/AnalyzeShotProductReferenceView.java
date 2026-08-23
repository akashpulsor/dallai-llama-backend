package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ProductReferenceClassification;

/** What one vision analysis call returns -- nothing persisted yet. The creator reviews this and
 * either discards it or POSTs it back (verbatim or edited) to {@code confirm}. */
public record AnalyzeShotProductReferenceView(
        String bucket,
        String objectKey,
        ProductReferenceClassification classification,
        String personDescription,
        String detectedSubject,
        String dominantMood,
        String referenceCameraAngle,
        String referenceLightingStyle,
        String referenceMotion
) {
}
