CREATE TABLE IF NOT EXISTS project (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(240) NOT NULL,
    locked_idea_id UUID NOT NULL,
    budget_tier VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN project.locked_idea_id IS
    'External reference to a locked idea in creative-planning-service -- never a local FK, that service is out of this bounded context.';

CREATE INDEX IF NOT EXISTS idx_project_tenant ON project (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS project_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL UNIQUE REFERENCES project(id) ON DELETE CASCADE,
    preferred_video_model VARCHAR(80),
    preferred_voice_model VARCHAR(80),
    preferred_lip_sync_model VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
