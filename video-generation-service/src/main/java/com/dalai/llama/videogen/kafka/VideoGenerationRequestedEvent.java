package com.dalai.llama.videogen.kafka;


import java.util.UUID;

/**
 * Published when a shot's generation is approved, consumed by this same service's worker side.
 *
 * <p>Carries the job id and the identity needed to rebuild a TenantContext, exactly as
 * {@link PrepareBatchRequestedEvent} does -- everything else about the shot lives on the
 * video_gen_job and shot_prompt rows, so a replayed or late event reads the request as recorded
 * rather than a stale copy baked into the payload.
 */
public record VideoGenerationRequestedEvent(
        UUID jobId,
        String tenantId,
        UUID projectId,
        UUID userId
) {
}
