package com.dalai.llama.postprod.dto;

import jakarta.validation.constraints.NotBlank;

/** sourceVideoUrl is optional -- some fal.ai foley models generate from a description alone,
 * others analyze the video; left to the caller/model being tested. */
public record GenerateFoleyRequest(
        String sourceVideoUrl,
        @NotBlank String cueDescription,
        String model
) {
}
