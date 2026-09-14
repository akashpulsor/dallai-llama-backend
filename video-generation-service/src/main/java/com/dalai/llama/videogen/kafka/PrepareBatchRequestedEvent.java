package com.dalai.llama.videogen.kafka;

import java.util.UUID;

/** Published when a prepare-batch is accepted, consumed by this same service's worker side.
 *
 * <p>Carries only the job id and the identity needed to rebuild a TenantContext -- everything
 * else about the request lives on the prepare_batch_job row, so a replayed or late event reads
 * the same parameters rather than a stale copy baked into the payload. */
public record PrepareBatchRequestedEvent(
        UUID jobId,
        String tenantId,
        UUID projectId,
        UUID userId
) {
}
