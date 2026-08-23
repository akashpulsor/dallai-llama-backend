package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ProductReferenceClassification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** The (possibly creator-edited) result of {@code analyze}, attached to the shot. */
public record ConfirmShotProductReferenceRequest(
        @NotBlank String bucket,
        @NotBlank String objectKey,
        @NotNull ProductReferenceClassification classification,
        String personDescription,
        String detectedSubject,
        String dominantMood,
        String referenceCameraAngle,
        String referenceLightingStyle,
        String referenceMotion,
        Boolean ignoreSubject
) {
}
