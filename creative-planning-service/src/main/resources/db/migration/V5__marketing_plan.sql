CREATE TABLE IF NOT EXISTS marketing_plan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    brand_context_id UUID NOT NULL REFERENCES brand_context(id) ON DELETE CASCADE,
    product_profile_id UUID REFERENCES product_profile(id) ON DELETE SET NULL,
    target_audience_input TEXT,
    budget_tier VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',

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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_marketing_plan_tenant ON marketing_plan (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS marketing_plan_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES marketing_plan(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_marketing_plan_message_plan ON marketing_plan_message (plan_id, created_at ASC);
