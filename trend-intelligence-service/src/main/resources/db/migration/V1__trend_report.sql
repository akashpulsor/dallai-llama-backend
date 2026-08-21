CREATE TABLE IF NOT EXISTS trend_report (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    topic TEXT NOT NULL,
    industry VARCHAR(200),
    target_audience TEXT,

    trend_summary TEXT,
    emerging_trends TEXT,
    declining_trends TEXT,
    opportunity_areas TEXT,
    risk_areas TEXT,
    recommended_actions TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trend_report_tenant ON trend_report (tenant_id, created_at DESC);
