-- Aggregate final-render lifecycle: one row per assembly attempt of a project's completed
-- shots into one deliverable mp4. Status reuses video_gen_job's JobStatus vocabulary
-- (PENDING_APPROVAL/PROCESSING/COMPLETED/FAILED/CANCELLED/TIMED_OUT) so reconciliation
-- helpers can treat both uniformly.
CREATE TABLE final_render_job (
    render_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    created_by UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    output_bucket VARCHAR(128),
    output_object_key VARCHAR(1024),
    shot_count INTEGER NOT NULL DEFAULT 0,
    source_job_ids VARCHAR(4096),
    actual_cost NUMERIC(18, 10),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    processing_started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

-- The "latest render for this project" query the UI runs on every video-workspace load.
CREATE INDEX idx_final_render_job_project_created ON final_render_job (project_id, created_at DESC);
CREATE INDEX idx_final_render_job_tenant ON final_render_job (tenant_id);
