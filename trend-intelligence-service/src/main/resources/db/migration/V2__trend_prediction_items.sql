-- Replaces the flat summary/emergingTrends/... columns with a predictions[] child table, matching
-- creator-service's established TREND_PREDICT response contract (title, summary,
-- confidenceScore, rationale, evidenceType, suggestedTags) rather than a bespoke shape.
ALTER TABLE trend_report
    DROP COLUMN IF EXISTS trend_summary,
    DROP COLUMN IF EXISTS emerging_trends,
    DROP COLUMN IF EXISTS declining_trends,
    DROP COLUMN IF EXISTS opportunity_areas,
    DROP COLUMN IF EXISTS risk_areas,
    DROP COLUMN IF EXISTS recommended_actions;

CREATE TABLE IF NOT EXISTS trend_prediction_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id UUID NOT NULL REFERENCES trend_report(id) ON DELETE CASCADE,
    item_order INTEGER NOT NULL,
    title TEXT NOT NULL,
    summary TEXT,
    confidence_score NUMERIC(3, 2),
    rationale TEXT,
    evidence_type VARCHAR(32),
    suggested_tags TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trend_prediction_item_report ON trend_prediction_item (report_id, item_order ASC);
