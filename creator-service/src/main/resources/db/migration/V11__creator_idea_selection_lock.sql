ALTER TABLE creator_ideas
    ADD COLUMN IF NOT EXISTS selection_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE creator_ideas
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ;

COMMENT ON COLUMN creator_ideas.selection_context IS
    'JSON memory of how the creator chose this idea: source type, platform/category/country/timeframe, selected trend snapshot, duration, and UI payload.';

COMMENT ON COLUMN creator_ideas.locked_at IS
    'Timestamp when the creator explicitly locked the trend or original idea as the active production brief.';

CREATE INDEX IF NOT EXISTS idx_creator_ideas_tenant_user_locked
    ON creator_ideas (tenant_id, user_id, locked_at DESC)
    WHERE status = 'LOCKED';

CREATE INDEX IF NOT EXISTS idx_creator_ideas_generated_parent
    ON creator_ideas ((selection_context ->> 'parentLockedIdeaId'), created_at)
    WHERE source = 'AI_FROM_LOCKED_BRIEF';
