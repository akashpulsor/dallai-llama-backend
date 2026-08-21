-- Two entry points (from a locked idea, or a standalone brief), one shared requirement/funding
-- flow. "funded" is a recorded state -- this schema has no notion of a payment method or
-- transaction, on purpose (see ProjectRequirement's own javadoc).
CREATE TABLE IF NOT EXISTS project_requirement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    tenant_type VARCHAR(16) NOT NULL,
    locked_idea_id UUID,
    brief_text TEXT NOT NULL,
    target_audience TEXT,
    campaign_direction TEXT,
    budget_tier VARCHAR(16) NOT NULL,
    share_token VARCHAR(64) NOT NULL UNIQUE,
    created_by UUID,
    funded BOOLEAN NOT NULL DEFAULT false,
    funded_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_requirement_tenant ON project_requirement (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS project_requirement_attachment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    requirement_id UUID NOT NULL REFERENCES project_requirement(id) ON DELETE CASCADE,
    bucket VARCHAR(200) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_requirement_attachment UNIQUE (bucket, object_key)
);

CREATE INDEX IF NOT EXISTS idx_project_requirement_attachment_requirement ON project_requirement_attachment (requirement_id);
