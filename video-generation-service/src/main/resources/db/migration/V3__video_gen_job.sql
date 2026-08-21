CREATE TABLE video_gen_job (
    job_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    project_id        UUID NOT NULL,
    created_by        UUID NOT NULL,
    shot_ref          VARCHAR(128) NOT NULL,
    provider_id       VARCHAR(64) NOT NULL,
    model_id          VARCHAR(128) NOT NULL,
    status            VARCHAR(32) NOT NULL,      -- PENDING_APPROVAL / PROCESSING / COMPLETED / FAILED / CANCELLED / REJECTED
    approval_status   VARCHAR(32) NOT NULL,      -- PENDING / APPROVED / REJECTED
    llm_gateway_job_id VARCHAR(64),
    output_uri        VARCHAR(1024),
    estimated_cost    NUMERIC(18, 10),
    actual_cost       NUMERIC(18, 10),
    cost_currency     VARCHAR(8),
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX idx_video_gen_job_tenant_project_created ON video_gen_job (tenant_id, project_id, created_at DESC);
CREATE INDEX idx_video_gen_job_tenant_shot_created ON video_gen_job (tenant_id, shot_ref, created_at DESC);
CREATE INDEX idx_video_gen_job_tenant_status ON video_gen_job (tenant_id, status);
