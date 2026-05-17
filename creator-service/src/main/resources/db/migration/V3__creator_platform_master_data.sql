ALTER TABLE creator_platforms
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS icon_key VARCHAR(80),
    ADD COLUMN IF NOT EXISTS sort_order INTEGER NOT NULL DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS visible BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS target_platform BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS signal_source BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS short_form BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS prompt_context TEXT;

CREATE INDEX IF NOT EXISTS idx_creator_platforms_visible
    ON creator_platforms (active, visible, target_platform, sort_order);

INSERT INTO creator_platforms (
    code,
    display_name,
    description,
    icon_key,
    sort_order,
    active,
    visible,
    target_platform,
    signal_source,
    short_form,
    prompt_context,
    status,
    metadata
)
VALUES
    ('instagram_reels', 'Instagram Reels', 'Target platform for Reels-style vertical short planning.', 'instagram', 10, true, true, true, false, true, 'Optimize for fast hooks, save/share behavior, caption-safe mobile framing, and Instagram Reels UI safe zones.', 'ACTIVE', '{"targetPlatform": true, "shortForm": true}'::jsonb),
    ('youtube_shorts', 'YouTube Shorts', 'Target platform for YouTube Shorts planning.', 'youtube', 20, true, true, true, false, true, 'Optimize for searchable titles, retention pacing, clean first-frame setup, and Shorts vertical playback.', 'ACTIVE', '{"targetPlatform": true, "shortForm": true}'::jsonb),
    ('facebook_reels', 'Facebook Reels', 'Target platform for Facebook Reels planning.', 'facebook', 30, true, true, true, false, true, 'Optimize for broad shareability, clear context, family-safe execution, and strong reaction beats.', 'ACTIVE', '{"targetPlatform": true, "shortForm": true}'::jsonb),
    ('google_trends', 'Google Trends', 'Signal source for trend collection, not a creator publishing target.', 'search', 900, true, false, false, true, false, 'Use as search demand signal only.', 'ACTIVE', '{"signalSource": true}'::jsonb),
    ('reddit', 'Reddit', 'Signal source for community conversation mining, not a creator publishing target.', 'message-circle', 910, true, false, false, true, false, 'Use as discussion intensity and audience language signal only.', 'ACTIVE', '{"signalSource": true}'::jsonb),
    ('youtube_public_pages', 'YouTube Public Pages', 'No-key source for public video and Shorts surface observations.', 'youtube', 920, true, false, false, true, false, 'Use public pages as a trend signal only.', 'ACTIVE', '{"signalSource": true}'::jsonb)
ON CONFLICT (code) DO UPDATE
SET display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    sort_order = EXCLUDED.sort_order,
    active = EXCLUDED.active,
    visible = EXCLUDED.visible,
    target_platform = EXCLUDED.target_platform,
    signal_source = EXCLUDED.signal_source,
    short_form = EXCLUDED.short_form,
    prompt_context = EXCLUDED.prompt_context,
    status = EXCLUDED.status,
    metadata = creator_platforms.metadata || EXCLUDED.metadata,
    updated_at = now();

UPDATE creator_trend_combinations
SET enabled = false,
    updated_at = now()
WHERE platform_code IN ('google_trends', 'reddit', 'youtube_public_pages');

WITH target_platforms AS (
    SELECT code
    FROM creator_platforms
    WHERE active = true
      AND target_platform = true
      AND visible = true
),
visible_categories AS (
    SELECT code
    FROM creator_categories
    WHERE active = true
      AND visible = true
)
INSERT INTO creator_trend_combinations (
    platform_code,
    category_code,
    enabled,
    free_source_policy,
    metadata
)
SELECT
    p.code,
    c.code,
    true,
    '{"sources": ["google_trends", "reddit", "youtube_public_pages"], "apiKeyRequired": false, "schedulerWindowMinutes": 30}'::jsonb,
    '{"seeded": true}'::jsonb
FROM target_platforms p
CROSS JOIN visible_categories c
ON CONFLICT (platform_code, category_code) DO UPDATE
SET enabled = true,
    free_source_policy = EXCLUDED.free_source_policy,
    metadata = creator_trend_combinations.metadata || EXCLUDED.metadata,
    updated_at = now();
