CREATE TABLE tenant_model_override (
    tenant_id     VARCHAR(128) NOT NULL,
    model_id      VARCHAR(128) NOT NULL REFERENCES model_master(model_id),
    rpm_override  INTEGER,
    tpm_override  INTEGER,
    allowed       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, model_id)
);
