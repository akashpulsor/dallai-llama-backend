package com.dalai.llama.joblifecycle;

import java.util.List;
import java.util.UUID;

/** What a service implements to plug its own multi-step generation into {@link
 * AbstractBatchWorker} -- the only service-specific code a batch actually needs. {@code S} is
 * whatever one step means for that service (e.g. pre-production-service's own {@code
 * record ShotAssetStep(UUID shotId, AssetKind kind)}); this interface never needs to know its
 * shape, only how to enumerate, run, label, and key it. */
public interface BatchStepExecutor<S> {

    /** The ordered, complete step list for one batch run -- recomputed fresh each tick from
     * {@code ownerId} rather than snapshotted (see {@link BatchJobRecord}'s javadoc), so it must
     * be stable for the life of a run (a project's shot list isn't expected to change mid-batch). */
    List<S> planSteps(UUID tenantId, UUID ownerId);

    /** Runs exactly one step. Throws on failure -- {@link AbstractBatchWorker} handles retry/DLQ,
     * this never needs to catch its own exceptions. */
    void execute(UUID tenantId, UUID ownerId, S step) throws Exception;

    /** Human-readable, e.g. "camera plan" -- goes into {@link BatchJobRecord#getCurrentStepLabel()}. */
    String label(S step);

    /** A canonical string identity for one step, e.g. "{@code <shotId>:CAMERA_PLAN}" -- what a
     * dead-lettered step is keyed by for display/lookup once it's out of the in-memory step list. */
    String stepKey(S step);
}
