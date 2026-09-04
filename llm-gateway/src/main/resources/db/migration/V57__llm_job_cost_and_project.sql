-- Persist raw per-call cost + project attribution on the job row itself, in real time at
-- completion (not just in audit_log / the transient response). This is what a cost rollup for
-- a project reads from. llm-gateway stays SRP-narrow: it records what each call cost and which
-- project it belonged to -- it does NOT categorize or price. project_id is nullable (plenty of
-- calls -- estimates, non-project internal calls -- have no project).
ALTER TABLE llm_job ADD COLUMN cost NUMERIC(18,6);
ALTER TABLE llm_job ADD COLUMN project_id UUID;

-- The aggregation query (job-costs endpoint) filters by tenant + project and groups by model,
-- joining model_master for the type -- index the filter columns.
CREATE INDEX idx_llm_job_tenant_project ON llm_job(tenant_id, project_id) WHERE project_id IS NOT NULL;
