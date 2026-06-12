CREATE TABLE IF NOT EXISTS creator_shot_takes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    script_id UUID NOT NULL REFERENCES creator_scripts(id) ON DELETE CASCADE,
    shot_number INTEGER NOT NULL,
    asset_id UUID NOT NULL REFERENCES creator_assets(id) ON DELETE CASCADE,
    status VARCHAR(40) NOT NULL DEFAULT 'UPLOADED',
    review_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    accepted BOOLEAN NOT NULL DEFAULT false,
    user_notes TEXT,
    validation_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_shot_takes_script_shot
    ON creator_shot_takes (script_id, shot_number, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_shot_takes_tenant_user
    ON creator_shot_takes (tenant_id, user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS creator_shot_take_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    take_id UUID NOT NULL REFERENCES creator_shot_takes(id) ON DELETE CASCADE,
    generation_job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    status VARCHAR(40) NOT NULL,
    score NUMERIC(5,2),
    checks JSONB NOT NULL DEFAULT '{}'::jsonb,
    sound_timeline JSONB NOT NULL DEFAULT '[]'::jsonb,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_shot_take_reviews_take
    ON creator_shot_take_reviews (take_id, created_at DESC);

CREATE TABLE IF NOT EXISTS creator_shot_enhancement_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    take_id UUID NOT NULL REFERENCES creator_shot_takes(id) ON DELETE CASCADE,
    generation_job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    preview_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    prompt_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    user_feedback TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_shot_enhancement_variants_take
    ON creator_shot_enhancement_variants (take_id, created_at DESC);

COMMENT ON TABLE creator_shot_takes IS 'User-recorded takes uploaded for a planned creator shot.';
COMMENT ON COLUMN creator_shot_takes.validation_summary IS 'Deterministic validation result and manual confirmation requirements for the uploaded take.';
COMMENT ON TABLE creator_shot_take_reviews IS 'Review runs for uploaded takes, including cheap validation checks and sound timeline context.';
COMMENT ON COLUMN creator_shot_take_reviews.sound_timeline IS 'Overlapping sound, foley, ambience, dialogue, and sync-hit layers for this shot.';
COMMENT ON TABLE creator_shot_enhancement_variants IS 'AI polish preview variants for accepted user-recorded takes.';
