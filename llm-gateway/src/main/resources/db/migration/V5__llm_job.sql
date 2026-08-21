CREATE TABLE llm_job (
    job_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        VARCHAR(128) NOT NULL,
    model_id         VARCHAR(128) NOT NULL REFERENCES model_master(model_id),
    status           VARCHAR(32) NOT NULL,
    mode             VARCHAR(16) NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    schema_version   VARCHAR(16) NOT NULL DEFAULT '1.0',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    last_error       TEXT,
    callback_url     VARCHAR(1024)
);

CREATE UNIQUE INDEX uq_llm_job_tenant_idempotency ON llm_job (tenant_id, idempotency_key);
CREATE INDEX idx_llm_job_tenant_created ON llm_job (tenant_id, created_at);
