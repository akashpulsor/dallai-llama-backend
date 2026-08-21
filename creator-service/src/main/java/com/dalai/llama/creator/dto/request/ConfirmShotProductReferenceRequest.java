package com.dalai.llama.creator.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConfirmShotProductReferenceRequest(
        @NotBlank String bucket,
        @NotBlank String objectKey,
        @NotBlank String url,
        @NotBlank @Pattern(regexp = "(?i)CAST|INSPIRATION", message = "classification must be CAST or INSPIRATION") String classification,
        @Size(max = 128) String castProfileId,
        @Size(max = 160) String castDisplayName,
        @Valid List<ApprovedUpdate> approvedUpdates,
        @NotNull Boolean ignoreSubject,
        @Size(max = 300) String detectedSubject,
        @Size(max = 200) String dominantMood,
        @Size(max = 200) String cameraAngle,
        @Size(max = 200) String lightingStyle,
        @Size(max = 200) String motion
) {
    public record ApprovedUpdate(
            @NotBlank @Size(max = 64) String field,
            @Size(max = 2000) String value
    ) {
    }
}
