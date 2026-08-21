CREATE TABLE IF NOT EXISTS generation_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    shot_id UUID NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    external_job_id UUID,
    external_prompt_id UUID,
    processing_started_at TIMESTAMPTZ,
    last_error TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_generation_job_shot ON generation_job (shot_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_generation_job_stale_scan ON generation_job (status, processing_started_at);
