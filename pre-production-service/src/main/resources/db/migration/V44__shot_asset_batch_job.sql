-- Backs the single "Generate all shot assets" CTA that replaces having to click a lighting-plan,
-- camera-plan, and 4-image button separately for every shot (confirmed live: 10 shots x ~7 CTAs
-- each is genuinely ~70 buttons on one page). Processed one step at a time by job-lifecycle-
-- common's generic BatchWorker, wired up here via ShotAssetBatchJobPersistenceService/
-- ShotAssetBatchWorker -- see that module for why the queue/retry/DLQ algorithm lives there
-- instead of being reinvented per feature (this table stays local: each service owns its own
-- schema, only the algorithm operating on it is shared). current_step_index is the worker's own
-- resume cursor into the project's ordered, eligible (non-MOTION_GRAPHIC) shots x 6-asset-kinds
-- step list -- recomputed fresh each tick rather than snapshotted, since nothing about a shot
-- list changes mid-batch in the normal case.
CREATE TABLE shot_asset_batch_job (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    total_steps INTEGER NOT NULL,
    completed_steps INTEGER NOT NULL DEFAULT 0,
    current_step_index INTEGER NOT NULL DEFAULT 0,
    -- Consecutive failures on the CURRENT step -- resets to 0 the moment that step either
    -- succeeds or exhausts MAX_ATTEMPTS and gets dead-lettered. Never counts against a step that
    -- already succeeded: this only ever tracks the one step the cursor is presently sitting on.
    current_step_attempt INTEGER NOT NULL DEFAULT 0,
    -- Count of steps that exhausted their automatic attempts and were dead-lettered (see
    -- shot_asset_batch_dead_letter below) -- what the progress UI's "N issues" actually means.
    error_count INTEGER NOT NULL DEFAULT 0,
    current_step_label TEXT,
    processing_started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shot_asset_batch_job_project_created ON shot_asset_batch_job (project_id, created_at DESC);
CREATE INDEX idx_shot_asset_batch_job_status_created ON shot_asset_batch_job (status, created_at ASC);

-- One row per step that failed MAX_ATTEMPTS (3) consecutive times during a batch run -- the
-- worker moves on past it rather than getting stuck retrying forever, but the failure isn't
-- silently dropped: it lands here, pending a deliberate, individual retry (see
-- ShotAssetBatchDeadLetterService#retry) once whatever caused it is fixed. Never auto-retried by
-- the worker itself -- only a direct POST against one dead-letter row runs that step again.
CREATE TABLE shot_asset_batch_dead_letter (
    id UUID PRIMARY KEY,
    batch_job_id UUID NOT NULL REFERENCES shot_asset_batch_job (id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    shot_id UUID NOT NULL,
    step VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL,
    last_error TEXT,
    resolved BOOLEAN NOT NULL DEFAULT false,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shot_asset_batch_dead_letter_project_unresolved ON shot_asset_batch_dead_letter (project_id, resolved);
