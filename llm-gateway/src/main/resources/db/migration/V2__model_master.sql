CREATE TABLE model_master (
    model_id            VARCHAR(128) PRIMARY KEY,
    provider_id         VARCHAR(64) NOT NULL REFERENCES provider(provider_id),
    type                VARCHAR(32) NOT NULL,
    capabilities        JSONB NOT NULL DEFAULT '{}'::jsonb,
    context_window      INTEGER,
    supports_streaming  BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(32) NOT NULL DEFAULT 'active',
    default_rpm         INTEGER NOT NULL,
    default_tpm         INTEGER NOT NULL,
    timeout_ms          INTEGER NOT NULL DEFAULT 30000,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_model_master_type_status ON model_master (type, status);
