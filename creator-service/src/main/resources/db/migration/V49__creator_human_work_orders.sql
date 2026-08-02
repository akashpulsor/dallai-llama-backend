CREATE TABLE IF NOT EXISTS creator_human_work_orders (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID,
    locked_idea_id UUID,
    story_idea_id UUID,
    script_id UUID REFERENCES creator_scripts(id) ON DELETE SET NULL,
    video_run_id UUID,
    work_type VARCHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    requester_notes TEXT,
    reviewer_notes TEXT,
    assigned_to VARCHAR(160),
    source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    delivery_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    conversation JSONB NOT NULL DEFAULT '[]'::jsonb,
    price_amount NUMERIC(12, 4) NOT NULL DEFAULT 0,
    price_currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    billing_status VARCHAR(40) NOT NULL DEFAULT 'NOT_REQUIRED',
    billing_reference VARCHAR(160),
    submitted_at TIMESTAMPTZ NOT NULL,
    assigned_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_creator_human_work_orders_queue
    ON creator_human_work_orders (work_type, status, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_human_work_orders_owner
    ON creator_human_work_orders (tenant_id, user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_human_work_orders_script
    ON creator_human_work_orders (script_id, updated_at DESC);

COMMENT ON TABLE creator_human_work_orders IS
    'Human screenplay review and freelancer editing work orders submitted from creator-ui and processed in reviewer/editor UI.';

COMMENT ON COLUMN creator_human_work_orders.source_payload IS
    'Snapshot of screenplay, final clip URLs, segregated assets, audio/music plan, captions, storyboard/video run, and editing instructions available to the human reviewer/editor.';

COMMENT ON COLUMN creator_human_work_orders.delivery_payload IS
    'Reviewer/editor output such as improved screenplay JSON/text, edited video URL, change summary, and uploaded asset references.';
