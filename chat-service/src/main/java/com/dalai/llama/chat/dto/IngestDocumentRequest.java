package com.dalai.llama.chat.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** What a producing service (creative-planning-service, critic-service, pre-production-service,
 * ...) posts to push one piece of "what happened to a project" into the central chat-facing
 * index. {@code sourceId} is a string, not a UUID, so it can carry whatever id shape the
 * producing service actually uses. Re-posting the same {@code (sourceService, sourceId, kind)}
 * for a tenant updates the existing row (e.g. a plan gets re-embedded after a revision) rather
 * than duplicating it. */
public record IngestDocumentRequest(
        @NotBlank String sourceService,
        @NotBlank String sourceId,
        @NotBlank String kind,
        UUID scopeId,
        @NotBlank String content
) {
}
