package com.dalai.llama.videogen.domain;

/** Project-scene preparation lifecycle. Toggled by the bulk shot-prepare loop so the UI can
 * poll {@code GET /v1/scenes/projects/{projectId}/preparation} and know whether a batch is
 * still running, finished cleanly, or gave up. Per-shot prepare doesn't touch this -- it's a
 * project-wide indicator, not a per-shot one. */
public enum ScenePreparationStatus {
    /** Stage 1 (project template) has run; no batch shot-prepare has been triggered yet. */
    PENDING,
    /** A bulk shot-prepare loop is currently walking through the project's shots. */
    PREPARING,
    /** The last bulk shot-prepare loop finished; some or all shots have prompts ready. Per-shot
     * failures (if any) are surfaced in the batch response itself, not this project-level flag. */
    READY,
    /** The last bulk shot-prepare loop failed before it could complete (an infrastructure or
     * upstream error, not a per-shot failure). */
    FAILED
}
