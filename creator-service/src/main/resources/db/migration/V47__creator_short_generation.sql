CREATE TABLE IF NOT EXISTS creator_short_videos (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID,
    source_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    generation_job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    title VARCHAR(240),
    original_file_name VARCHAR(260),
    platform VARCHAR(64),
    target_duration_seconds INTEGER,
    requested_shorts INTEGER,
    review_mode VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    video_dna JSONB NOT NULL DEFAULT '{}'::jsonb,
    transcript_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    graph_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_creator_short_videos_tenant_user_created
    ON creator_short_videos (tenant_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_short_videos_job
    ON creator_short_videos (generation_job_id);

CREATE TABLE IF NOT EXISTS creator_short_candidates (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    video_id UUID NOT NULL REFERENCES creator_short_videos(id) ON DELETE CASCADE,
    generation_job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    rank_index INTEGER NOT NULL,
    title VARCHAR(240) NOT NULL,
    duration_seconds INTEGER,
    score NUMERIC(6, 3),
    hook_type VARCHAR(80),
    status VARCHAR(32) NOT NULL DEFAULT 'READY_FOR_REVIEW',
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    edit_decision_list JSONB NOT NULL DEFAULT '{}'::jsonb,
    caption_plan JSONB NOT NULL DEFAULT '{}'::jsonb,
    render_manifest JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_short_candidates_video_rank
    ON creator_short_candidates (video_id, rank_index ASC);

CREATE INDEX IF NOT EXISTS idx_creator_short_candidates_tenant_user_created
    ON creator_short_candidates (tenant_id, user_id, created_at DESC);

COMMENT ON TABLE creator_short_videos IS 'Long-video source records and reusable agent analysis state for Generate Shorts.';
COMMENT ON TABLE creator_short_candidates IS 'Ranked short-video candidates generated from creator_short_videos with review and render manifests.';