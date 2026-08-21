CREATE TABLE audit_log (
    audit_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id         UUID NOT NULL REFERENCES llm_job(job_id),
    tenant_id      VARCHAR(128) NOT NULL,
    model_id       VARCHAR(128) NOT NULL,
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    cost           NUMERIC(18, 10),
    latency_ms     INTEGER,
    status         VARCHAR(32) NOT NULL,
    error          TEXT,
    payload_uri    VARCHAR(1024),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_tenant_created ON audit_log (tenant_id, created_at);
