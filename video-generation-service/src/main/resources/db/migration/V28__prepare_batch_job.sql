-- One row per prepare-batch request, tracking it from PENDING to SUCCEEDED/FAILED.
--
-- The first cut of async prepare ran the batch on an in-process thread pool. That is not how this
-- codebase defers work: pre-production-service persists a job row and hands the work to Kafka,
-- letting the consumer group be the concurrency mechanism (see ChatJobCompletedConsumer's own
-- comment on the point). A thread pool loses every queued batch when the pod restarts, cannot be
-- tuned without a redeploy, and does not coordinate across replicas -- two pods would each happily
-- run their own batch for the same project.
--
-- This row is the durable record: it survives the restart, it is what the UI polls, and a batch
-- stranded by a lost event is visible as a PENDING row rather than silently never happening.
CREATE TABLE prepare_batch_job (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    project_id           UUID NOT NULL,
    created_by           UUID,
    status               VARCHAR(16) NOT NULL,
    -- NULL means "every shot in the project", the same contract the endpoint takes. Stored as
    -- text rather than a UUID[] so the row carries exactly what was requested, replayable as-is.
    shot_ids             TEXT,
    model_pin            VARCHAR(128),
    resolution_override  VARCHAR(16),
    dialogue_flag        VARCHAR(8),
    captions_flag        VARCHAR(8),
    -- Updated as each shot finishes, not only at the end, so the UI can show "4 of 13" rather
    -- than an unmoving spinner for the couple of minutes a batch takes.
    prepared_count       INTEGER NOT NULL DEFAULT 0,
    failed_count         INTEGER NOT NULL DEFAULT 0,
    -- How many shots this batch will attempt. Not known until the consumer has the prepare
    -- bundle (an empty shotIds means "every shot in the project"), so it stays null until then.
    total_count          INTEGER,
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ
);

-- The UI asks "what is the latest batch for this project" on every poll.
CREATE INDEX idx_prepare_batch_job_project ON prepare_batch_job (project_id, created_at DESC);

-- Refusing a second batch while one is live is a correctness rule, not a convenience: two
-- concurrent batches for a project redo the same shots and bill for both. A partial unique index
-- enforces it in the database, where it holds across replicas -- the in-process Set it replaces
-- only ever guarded a single pod.
CREATE UNIQUE INDEX idx_prepare_batch_job_one_live_per_project
    ON prepare_batch_job (project_id)
    WHERE status IN ('PENDING', 'RUNNING');
