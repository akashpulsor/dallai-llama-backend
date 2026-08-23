package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors chat-service's {@code PreProductionServiceClient.SuggestChangeRequest} exactly -- each
 * service owns its own copy of this cross-service shape, same convention used everywhere else a
 * request crosses a service boundary in this codebase. */
public record SuggestChangeRequestRequest(
        @NotBlank String targetType,
        String targetRef,
        @NotBlank String note
) {
}
