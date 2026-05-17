CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS creator_platforms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_trend_combinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    free_source_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_trend_combination UNIQUE (platform_code, category_code)
);

CREATE TABLE IF NOT EXISTS creator_projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    selected_platform_code VARCHAR(64),
    selected_category_code VARCHAR(64),
    timeframe VARCHAR(64) NOT NULL DEFAULT 'LAST_7_DAYS',
    country_code VARCHAR(16) NOT NULL DEFAULT 'IN',
    duration_seconds INTEGER,
    selected_trend_id UUID,
    selected_audience_id UUID,
    selected_profile_id UUID,
    selected_idea_id UUID,
    selected_storyboard_id UUID,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    memory_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_trend_dumps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    country_code VARCHAR(16) NOT NULL DEFAULT 'IN',
    window_started_at TIMESTAMPTZ NOT NULL,
    window_ended_at TIMESTAMPTZ NOT NULL,
    source_name VARCHAR(96) NOT NULL,
    source_url TEXT,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'COLLECTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_trends (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_dump_id UUID REFERENCES creator_trend_dumps(id) ON DELETE SET NULL,
    platform_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    country_code VARCHAR(16) NOT NULL DEFAULT 'IN',
    title VARCHAR(240) NOT NULL,
    summary TEXT,
    source_name VARCHAR(96),
    source_url TEXT,
    score NUMERIC(10, 2) NOT NULL DEFAULT 0,
    velocity NUMERIC(10, 2) NOT NULL DEFAULT 0,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    prediction_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_audiences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    demographics JSONB NOT NULL DEFAULT '{}'::jsonb,
    interests JSONB NOT NULL DEFAULT '[]'::jsonb,
    psychographics JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_preference VARCHAR(240),
    ai_suggested BOOLEAN NOT NULL DEFAULT false,
    confirmed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    display_name VARCHAR(160) NOT NULL,
    role_in_short VARCHAR(120),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    confirmed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_ideas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE CASCADE,
    trend_id UUID REFERENCES creator_trends(id) ON DELETE SET NULL,
    source VARCHAR(48) NOT NULL DEFAULT 'MANUAL',
    title VARCHAR(240) NOT NULL,
    summary TEXT,
    script TEXT,
    scenes JSONB NOT NULL DEFAULT '[]'::jsonb,
    duration_seconds INTEGER,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    saved BOOLEAN NOT NULL DEFAULT false,
    generation_job_id UUID,
    prompt_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_storyboards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE CASCADE,
    idea_id UUID REFERENCES creator_ideas(id) ON DELETE SET NULL,
    title VARCHAR(240) NOT NULL,
    duration_seconds INTEGER NOT NULL,
    total_shots INTEGER NOT NULL DEFAULT 0,
    pacing_style TEXT,
    emotional_arc TEXT,
    hook_strategy TEXT,
    creator_fit_reasoning TEXT,
    audience_fit_reasoning TEXT,
    overall_execution_difficulty VARCHAR(120),
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    saved BOOLEAN NOT NULL DEFAULT false,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128),
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    storyboard_id UUID REFERENCES creator_storyboards(id) ON DELETE SET NULL,
    asset_type VARCHAR(64) NOT NULL,
    bucket VARCHAR(160) NOT NULL,
    object_key TEXT NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT,
    public_url TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_asset_object UNIQUE (bucket, object_key)
);

CREATE TABLE IF NOT EXISTS creator_storyboard_scenes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    storyboard_id UUID NOT NULL REFERENCES creator_storyboards(id) ON DELETE CASCADE,
    image_asset_id UUID REFERENCES creator_assets(id) ON DELETE SET NULL,
    shot_number INTEGER NOT NULL,
    start_time VARCHAR(16),
    end_time VARCHAR(16),
    duration_seconds INTEGER,
    title VARCHAR(240) NOT NULL,
    purpose TEXT,
    shot_type VARCHAR(120),
    camera_angle VARCHAR(160),
    camera_movement VARCHAR(160),
    lens_suggestion VARCHAR(160),
    fps INTEGER,
    composition TEXT,
    expression JSONB NOT NULL DEFAULT '{}'::jsonb,
    emotion JSONB NOT NULL DEFAULT '[]'::jsonb,
    body_language JSONB NOT NULL DEFAULT '{}'::jsonb,
    lighting TEXT,
    environment TEXT,
    action TEXT,
    voice_over TEXT,
    dialogue JSONB NOT NULL DEFAULT '{}'::jsonb,
    text_overlay TEXT,
    transition VARCHAR(160),
    sound_design JSONB NOT NULL DEFAULT '[]'::jsonb,
    editing_notes JSONB NOT NULL DEFAULT '[]'::jsonb,
    retention_goal TEXT,
    creator_direction JSONB NOT NULL DEFAULT '{}'::jsonb,
    subtitle_position VARCHAR(80),
    mobile_focus_area VARCHAR(120),
    safe_zone_notes TEXT,
    execution_difficulty JSONB NOT NULL DEFAULT '{}'::jsonb,
    cinematic_execution JSONB NOT NULL DEFAULT '{}'::jsonb,
    rookie_friendly_guide JSONB NOT NULL DEFAULT '{}'::jsonb,
    sketch_prompt TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_scene_shot UNIQUE (storyboard_id, shot_number)
);

CREATE TABLE IF NOT EXISTS creator_generation_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    progress INTEGER NOT NULL DEFAULT 0,
    redis_key VARCHAR(240),
    kafka_topic VARCHAR(160),
    input_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS creator_prompt_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key VARCHAR(80) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(200) NOT NULL,
    template_body TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_prompt_template_version UNIQUE (template_key, version)
);

CREATE TABLE IF NOT EXISTS creator_prompt_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    job_id UUID REFERENCES creator_generation_jobs(id) ON DELETE SET NULL,
    prompt_template_id UUID REFERENCES creator_prompt_templates(id) ON DELETE SET NULL,
    prompt_template_key VARCHAR(80) NOT NULL,
    prompt_template_version INTEGER NOT NULL,
    rendered_prompt TEXT NOT NULL,
    input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(120) NOT NULL,
    output_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    token_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    cost_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS creator_activity_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    project_id UUID REFERENCES creator_projects(id) ON DELETE SET NULL,
    event_type VARCHAR(80) NOT NULL,
    event_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_saved_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    item_type VARCHAR(48) NOT NULL,
    item_id UUID NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_saved_item UNIQUE (tenant_id, user_id, item_type, item_id)
);

CREATE INDEX IF NOT EXISTS idx_creator_projects_tenant_user ON creator_projects (tenant_id, user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_trends_combo ON creator_trends (platform_code, category_code, country_code, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_trend_dumps_combo ON creator_trend_dumps (platform_code, category_code, country_code, window_ended_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_audiences_project ON creator_audiences (project_id, confirmed);
CREATE INDEX IF NOT EXISTS idx_creator_profiles_tenant_user ON creator_profiles (tenant_id, user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_ideas_project ON creator_ideas (project_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_storyboards_project ON creator_storyboards (project_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_generation_jobs_project ON creator_generation_jobs (project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_prompt_runs_project ON creator_prompt_runs (project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_creator_activity_events_project ON creator_activity_events (project_id, created_at DESC);

INSERT INTO creator_platforms (code, display_name, metadata)
VALUES
    ('instagram_reels', 'Instagram Reels', '{"shortForm": true}'::jsonb),
    ('youtube_shorts', 'YouTube Shorts', '{"shortForm": true}'::jsonb),
    ('reddit', 'Reddit', '{"signalSource": true}'::jsonb),
    ('google_trends', 'Google Trends', '{"signalSource": true}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO creator_categories (code, display_name, metadata)
VALUES
    ('fitness', 'Fitness', '{}'::jsonb),
    ('beauty', 'Beauty', '{}'::jsonb),
    ('food', 'Food', '{}'::jsonb),
    ('family_comedy', 'Family Comedy', '{}'::jsonb),
    ('finance', 'Finance', '{}'::jsonb),
    ('creator_growth', 'Creator Growth', '{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO creator_trend_combinations (platform_code, category_code, free_source_policy)
VALUES
    ('instagram_reels', 'fitness', '{"sources": ["google_trends", "reddit", "youtube_public_pages"], "apiKeyRequired": false}'::jsonb),
    ('instagram_reels', 'beauty', '{"sources": ["google_trends", "reddit", "youtube_public_pages"], "apiKeyRequired": false}'::jsonb),
    ('instagram_reels', 'food', '{"sources": ["google_trends", "reddit", "youtube_public_pages"], "apiKeyRequired": false}'::jsonb),
    ('youtube_shorts', 'family_comedy', '{"sources": ["google_trends", "reddit", "youtube_public_pages"], "apiKeyRequired": false}'::jsonb),
    ('youtube_shorts', 'creator_growth', '{"sources": ["google_trends", "reddit", "youtube_public_pages"], "apiKeyRequired": false}'::jsonb),
    ('google_trends', 'finance', '{"sources": ["google_trends", "reddit"], "apiKeyRequired": false}'::jsonb)
ON CONFLICT (platform_code, category_code) DO NOTHING;

INSERT INTO creator_prompt_templates (template_key, version, name, template_body)
VALUES
    ('TREND_REFRESH', 1, 'Predict trends from recent dumps',
     'Use the last 30 minutes of trend dumps for {{platform}} / {{category}} / {{country}}. Predict creator-safe short-form trends and return structured JSON.'),
    ('AUDIENCE_SUGGEST', 1, 'Suggest target audience',
     'Given project memory {{projectMemory}} and selected trend {{trend}}, suggest one precise target audience with demographics, interests, and content preference.'),
    ('IDEA_GENERATE', 1, 'Generate short concept ideas',
     'Given trend, audience, creator profile, and optional creator script, generate short-form ideas with scene-by-scene script candidates.'),
    ('STORYBOARD_GENERATE', 1, 'Generate director storyboard',
     'Generate a {{durationSeconds}} second vertical short storyboard. Every shot must include director details: action, dialogue, expression, camera, lighting, editing, retention goal, rookie guide, and sketch prompt.'),
    ('SCENE_REGENERATE', 1, 'Regenerate one storyboard scene',
     'Regenerate only scene {{sceneId}} using full storyboard context and preserve timing continuity.'),
    ('EXPORT_SUMMARY', 1, 'Create export package summary',
     'Summarize the storyboard into a creator production package with shot list, captions, asset references, and execution checklist.')
ON CONFLICT (template_key, version) DO NOTHING;
