CREATE TABLE IF NOT EXISTS campaign_planning_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    brand_context_id UUID NOT NULL REFERENCES brand_context(id) ON DELETE CASCADE,
    product_profile_id UUID REFERENCES product_profile(id) ON DELETE SET NULL,
    budget_tier VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_campaign_session_tenant ON campaign_planning_session (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS campaign_planning_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES campaign_planning_session(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_campaign_message_session ON campaign_planning_message (session_id, created_at ASC);

CREATE TABLE IF NOT EXISTS locked_idea (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    session_id UUID NOT NULL UNIQUE REFERENCES campaign_planning_session(id) ON DELETE CASCADE,
    title VARCHAR(240) NOT NULL,
    concept TEXT,
    target_audience TEXT,
    campaign_angle TEXT,
    key_message TEXT,
    tone VARCHAR(240),
    budget_tier VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
