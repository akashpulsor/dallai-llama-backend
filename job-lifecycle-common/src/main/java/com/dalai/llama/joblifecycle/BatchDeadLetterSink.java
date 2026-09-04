package com.dalai.llama.joblifecycle;

import java.util.UUID;

/** Where a step lands after failing {@code AbstractBatchWorker.maxAttempts} consecutive times --
 * implemented against whatever dead-letter table the owning service keeps (a real per-service JPA
 * entity/table, not something this shared module can own itself, since each service has its own
 * schema). Never called by anything except {@link AbstractBatchWorker} on final failure; a
 * dead-lettered step is never retried automatically afterward -- only a direct, individual action
 * against that row runs the step again. */
public interface BatchDeadLetterSink<S> {

    void deadLetter(UUID jobId, UUID tenantId, UUID ownerId, S step, String stepKey, int attempts, String lastError);
}
