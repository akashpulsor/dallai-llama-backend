CREATE TABLE project_config (
    project_id             UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    default_dialogue_flag  VARCHAR(8) NOT NULL DEFAULT 'ON',
    default_captions_flag  VARCHAR(8) NOT NULL DEFAULT 'OFF',
    auto_approve           BOOLEAN NOT NULL DEFAULT false,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_config_tenant ON project_config (tenant_id);
