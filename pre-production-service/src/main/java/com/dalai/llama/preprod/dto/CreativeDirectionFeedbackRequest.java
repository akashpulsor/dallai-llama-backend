package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A review note on one treatment. {@code requestRevision} marks the treatment REVISION_REQUESTED;
 * the revision itself is a separate, explicit request so a reviewer can leave several notes first. */
public record CreativeDirectionFeedbackRequest(
        @NotBlank @Size(max = 4000) String feedback,
        boolean requestRevision
) {
}
