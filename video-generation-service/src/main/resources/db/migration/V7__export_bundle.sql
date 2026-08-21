CREATE TABLE export_bundle (
    bundle_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    project_id   UUID NOT NULL,
    prompt_id    UUID NOT NULL REFERENCES shot_prompt(prompt_id),
    status       VARCHAR(32) NOT NULL, -- BUILDING / READY / FAILED
    object_key   VARCHAR(1024),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_export_bundle_tenant_project_expires ON export_bundle (tenant_id, project_id, expires_at);
