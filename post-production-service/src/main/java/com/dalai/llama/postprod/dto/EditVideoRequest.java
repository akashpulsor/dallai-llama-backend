package com.dalai.llama.postprod.dto;

import jakarta.validation.constraints.NotBlank;

/** startSeconds/endSeconds both null edits the whole clip; both set edits only that portion. */
public record EditVideoRequest(
        @NotBlank String sourceVideoUrl,
        Integer startSeconds,
        Integer endSeconds,
        @NotBlank String editInstruction,
        String referenceImageUrl,
        String model
) {
}
