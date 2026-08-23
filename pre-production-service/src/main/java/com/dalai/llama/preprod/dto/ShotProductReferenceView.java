package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ProductReferenceClassification;

import java.util.UUID;

public record ShotProductReferenceView(
        UUID id,
        UUID shotId,
        ProductReferenceClassification classification,
        String bucket,
        String objectKey,
        String signedUrl,
        String personDescription,
        String detectedSubject,
        String dominantMood,
        String referenceCameraAngle,
        String referenceLightingStyle,
        String referenceMotion,
        Boolean ignoreSubject
) {
}
