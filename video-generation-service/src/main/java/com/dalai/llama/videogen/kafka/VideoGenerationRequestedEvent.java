package com.dalai.llama.videogen.kafka;

import com.dalai.llama.videogen.service.dialoguefit.DialogueFitChoice;

import java.util.UUID;

/**
 * Published when a shot's generation is approved, consumed by this same service's worker side.
 *
 * <p>Carries the job id and the identity needed to rebuild a TenantContext, exactly as
 * {@link PrepareBatchRequestedEvent} does -- everything else about the shot lives on the
 * video_gen_job and shot_prompt rows, so a replayed or late event reads the request as recorded
 * rather than a stale copy baked into the payload.
 *
 * <p>{@code fitChoice} is the exception, and has to be here: it is a decision made at the moment of
 * approving -- give the shot the seconds its line needs, or generate as planned and accept the line
 * being hurried -- and it is not written to any row. Losing it would silently fall back to a
 * default the creator did not choose.
 */
public record VideoGenerationRequestedEvent(
        UUID jobId,
        String tenantId,
        UUID projectId,
        UUID userId,
        DialogueFitChoice fitChoice
) {
}
