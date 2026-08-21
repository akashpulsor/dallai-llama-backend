CREATE TABLE IF NOT EXISTS critique_session (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    project_id UUID NOT NULL,
    shot_id UUID NOT NULL,
    original_plan_json TEXT NOT NULL,
    revised_plan_json TEXT,
    verdict VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_critique_session_shot ON critique_session (shot_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_critique_session_tenant ON critique_session (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS critique_finding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES critique_session(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL,
    observation TEXT NOT NULL,
    risk TEXT,
    cause TEXT,
    correction TEXT,
    severity VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_critique_finding_session ON critique_finding (session_id);
