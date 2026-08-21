CREATE TABLE dubbing_job (
    job_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    created_by            UUID NOT NULL,
    source_bucket         VARCHAR(128) NOT NULL,
    source_object_key     VARCHAR(1024) NOT NULL,
    target_language       VARCHAR(16) NOT NULL,
    source_language       VARCHAR(16),
    transcript            TEXT,
    translated_transcript TEXT,
    status                VARCHAR(32) NOT NULL,
    output_bucket         VARCHAR(128),
    output_object_key     VARCHAR(1024),
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_started_at TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ
);

CREATE INDEX idx_dubbing_job_tenant_created ON dubbing_job (tenant_id, created_at DESC);
CREATE INDEX idx_dubbing_job_status_processing_started ON dubbing_job (status, processing_started_at);
