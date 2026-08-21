CREATE TABLE post_production_job (
    job_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    project_id            UUID NOT NULL,
    created_by            UUID NOT NULL,
    shot_ref              VARCHAR(128) NOT NULL,
    scope                 VARCHAR(16) NOT NULL,      -- PROJECT / SHOT
    status                VARCHAR(32) NOT NULL,      -- PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED / TIMED_OUT
    video_gen_job_id      UUID NOT NULL,
    dialogue_sync_job_id  UUID,
    output_bucket         VARCHAR(128),
    output_object_key     VARCHAR(1024),
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_started_at TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ
);

CREATE INDEX idx_post_production_job_tenant_project_created ON post_production_job (tenant_id, project_id, created_at DESC);
CREATE INDEX idx_post_production_job_status_processing_started ON post_production_job (status, processing_started_at);
