-- Marketing-plan critique harness: same relational-only discipline as the shot-critique schema
-- (V1/V3) -- no jsonb, every section its own typed column.
CREATE TABLE IF NOT EXISTS marketing_plan_critique_session (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    verdict VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_marketing_plan_critique_session_plan ON marketing_plan_critique_session (plan_id);
CREATE INDEX IF NOT EXISTS idx_marketing_plan_critique_session_tenant ON marketing_plan_critique_session (tenant_id);

CREATE TABLE IF NOT EXISTS marketing_plan_snapshot (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES marketing_plan_critique_session(id) ON DELETE CASCADE,
    kind VARCHAR(8) NOT NULL,

    executive_summary TEXT,
    market_analysis TEXT,
    target_audience_profile TEXT,
    positioning_statement TEXT,
    brand_strategy TEXT,
    marketing_objectives TEXT,
    channel_strategy TEXT,
    content_strategy TEXT,
    budget_guidance TEXT,
    success_metrics TEXT,
    risks_and_mitigations TEXT,
    referenced_case_study_patterns TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_plan_snapshot UNIQUE (session_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_marketing_plan_snapshot_session ON marketing_plan_snapshot (session_id);

CREATE TABLE IF NOT EXISTS marketing_plan_critique_finding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES marketing_plan_critique_session(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL,
    observation TEXT NOT NULL,
    risk TEXT,
    cause TEXT,
    correction TEXT,
    severity VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_marketing_plan_critique_finding_session ON marketing_plan_critique_finding (session_id);
