CREATE TABLE IF NOT EXISTS creator_product_ad_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    locked_idea_id UUID,
    story_idea_id UUID,
    script_id UUID,
    generation_job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    media_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    asset_kind VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(80),
    model VARCHAR(160),
    prompt TEXT,
    asset_url TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_product_ad_assets_job
    ON creator_product_ad_assets (generation_job_id, created_at);

CREATE INDEX IF NOT EXISTS idx_creator_product_ad_assets_tenant_user_created
    ON creator_product_ad_assets (tenant_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_product_ad_assets_script_created
    ON creator_product_ad_assets (script_id, created_at DESC)
    WHERE script_id IS NOT NULL;

COMMENT ON TABLE creator_product_ad_assets IS
    'Generated product-ad image anchors and their independent status for product URL/name to commercial workflows.';
