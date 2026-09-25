-- Async job tracking for POST /v1/project-requirements/{id}/ideas/generate. One row per submit.
-- The controller inserts PENDING, publishes to Kafka llm.job.requested (llm-gateway's worker does
-- the LLM call and publishes to llm.job.completed), and this service's ChatJobCompletedConsumer
-- flips the row to SUCCEEDED (running the same idea persistence + critic path the old sync
-- endpoint used to run inline) or FAILED. The UI polls GET
-- /v1/project-requirements/{id}/ideas/generate/{jobId} to see when it lands.
--
-- Motivation: the old sync endpoint blew past Istio's 10s per-try timeout on every LLM call and
-- returned 504 UT upstream_per_try_timeout. Yesterday's chart change widened the route to 95s as
-- a stopgap; this migration removes the timeout pressure entirely by moving the work off the
-- request thread, matching the shot-list-job pattern in pre-production-service (see V49 there).
--
-- llm_job_idempotency_key is the correlation anchor -- sent to llm-gateway as its idempotency
-- key AND carried through the Kafka completed event; the consumer joins on it to know which
-- local job a completion belongs to.
CREATE TABLE idea_generation_job (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL,
    project_requirement_id      UUID NOT NULL,
    requested_option_count      INTEGER,
    status                      VARCHAR(16) NOT NULL,
    llm_job_idempotency_key     VARCHAR(255) NOT NULL,
    error_message               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at                TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_idea_generation_job_llm_idempotency_key ON idea_generation_job(llm_job_idempotency_key);
CREATE INDEX idx_idea_generation_job_requirement ON idea_generation_job(tenant_id, project_requirement_id, created_at DESC);
