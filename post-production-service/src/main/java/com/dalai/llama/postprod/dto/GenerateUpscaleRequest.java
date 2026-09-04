package com.dalai.llama.postprod.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** model is optional -- omit it to use the configured default-upscale-model; pass one of the
 * ids returned by GET /v1/post-production/models?type=upscale to pick a specific catalog
 * entry instead. durationSeconds is the source clip's real duration (the caller -- creator-ui's
 * Patch Editor -- already knows this from probing the loaded video) -- required so llm-gateway's
 * duration-priced billing for this model can compute a real cost instead of $0; not something
 * post-production-service can derive itself without downloading and probing the video. */
public record GenerateUpscaleRequest(
        @NotBlank String sourceVideoUrl,
        String model,
        @NotNull @Positive Double durationSeconds
) {
}
