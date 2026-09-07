-- Tracks each async shot-list generation request. One row per POST to /v1/projects/{projectId}/shots/generate-list.
-- The controller inserts this row PENDING, publishes to Kafka topic llm.job.requested (llm-gateway's worker consumes,
-- calls Gemini, publishes to llm.job.completed), and this service's ChatJobCompletedConsumer flips the row to
-- SUCCEEDED (running the same shot-list persistence code the old synchronous endpoint used to run inline) or FAILED.
-- The UI polls GET /v1/projects/{projectId}/shots/generate-list/{jobId} against this row.
--
-- llm_job_idempotency_key is the correlation anchor: it's the "shot-list-generate-{projectId}-{jobId}" string sent to
-- llm-gateway as its Idempotency-Key header AND carried through the Kafka completed event -- the consumer looks up
-- ShotListJob rows by this to know which local job the arriving completed event belongs to.
CREATE TABLE shot_list_job (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL,
    project_id                  UUID NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    llm_job_idempotency_key     VARCHAR(255) NOT NULL,
    error_message               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at                TIMESTAMPTZ
);

-- Query pattern 1: the UI polls one specific job; hitting this by primary key is already fastest.
-- Query pattern 2: the ChatJobCompletedConsumer looks up "which of my rows does this event belong to?" by the
-- idempotency key that llm-gateway carried through -- unique index both enforces "one active job per key" and
-- makes that lookup O(log n).
CREATE UNIQUE INDEX ux_shot_list_job_llm_idempotency_key ON shot_list_job(llm_job_idempotency_key);

-- Query pattern 3: "what's the latest job for this project?" -- lets a future retry-safe controller reuse an
-- in-flight job instead of starting a duplicate.
CREATE INDEX idx_shot_list_job_project ON shot_list_job(tenant_id, project_id, created_at DESC);
