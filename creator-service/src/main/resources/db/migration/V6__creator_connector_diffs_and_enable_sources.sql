CREATE TABLE IF NOT EXISTS creator_connector_run_diffs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_run_id UUID REFERENCES creator_connector_runs(id) ON DELETE CASCADE,
    connector_code VARCHAR(96) NOT NULL,
    target_platform_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    country_code VARCHAR(16) NOT NULL DEFAULT 'IN',
    incoming_count INTEGER NOT NULL DEFAULT 0,
    new_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    changed_count INTEGER NOT NULL DEFAULT 0,
    missing_count INTEGER NOT NULL DEFAULT 0,
    diff_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_connector_run_diffs_lookup
    ON creator_connector_run_diffs (target_platform_code, category_code, connector_code, created_at DESC);

UPDATE creator_source_connectors
SET enabled = true,
    config_json = config_json || '{"enabledAfterParser": true}'::jsonb,
    updated_at = now()
WHERE code IN (
    'google_trends_web',
    'reddit_public_json',
    'reddit_rss',
    'youtube_public_pages',
    'coingecko_public_api',
    'binance_public_api'
);

UPDATE creator_source_connectors
SET request_template = '{"path": "/trends/api/dailytrends", "query": {"hl": "en-US", "tz": "-330", "geo": "{{countryCode}}", "ns": "15"}}'::jsonb,
    parser_type = 'JSON',
    updated_at = now()
WHERE code = 'google_trends_web';

WITH connector_categories(connector_code, category_code, enabled, priority, weight) AS (
    VALUES
        ('google_trends_web', 'fitness', true, 10, 1.00),
        ('google_trends_web', 'beauty', true, 10, 1.00),
        ('google_trends_web', 'food', true, 10, 1.00),
        ('google_trends_web', 'study', true, 10, 1.00),
        ('google_trends_web', 'fashion', true, 10, 1.00),
        ('google_trends_web', 'tech_ai', true, 10, 1.00),
        ('google_trends_web', 'finance_crypto', true, 10, 1.00),
        ('google_trends_web', 'business_startup', true, 10, 1.00),
        ('google_trends_web', 'entertainment', true, 10, 1.00),
        ('google_trends_web', 'travel', true, 10, 1.00),
        ('google_trends_web', 'lifestyle_home', true, 10, 1.00),
        ('google_trends_web', 'self_improvement', true, 10, 1.00),

        ('reddit_public_json', 'fitness', true, 20, 0.90),
        ('reddit_public_json', 'beauty', true, 20, 0.90),
        ('reddit_public_json', 'food', true, 20, 0.90),
        ('reddit_public_json', 'study', true, 20, 0.90),
        ('reddit_public_json', 'fashion', true, 20, 0.90),
        ('reddit_public_json', 'tech_ai', true, 20, 0.90),
        ('reddit_public_json', 'finance_crypto', true, 20, 0.90),
        ('reddit_public_json', 'business_startup', true, 20, 0.90),
        ('reddit_public_json', 'entertainment', true, 20, 0.90),
        ('reddit_public_json', 'travel', true, 20, 0.90),
        ('reddit_public_json', 'lifestyle_home', true, 20, 0.90),
        ('reddit_public_json', 'self_improvement', true, 20, 0.90),

        ('reddit_rss', 'fitness', true, 30, 0.75),
        ('reddit_rss', 'beauty', true, 30, 0.75),
        ('reddit_rss', 'food', true, 30, 0.75),
        ('reddit_rss', 'study', true, 30, 0.75),
        ('reddit_rss', 'fashion', true, 30, 0.75),
        ('reddit_rss', 'tech_ai', true, 30, 0.75),
        ('reddit_rss', 'finance_crypto', true, 30, 0.75),
        ('reddit_rss', 'business_startup', true, 30, 0.75),
        ('reddit_rss', 'entertainment', true, 30, 0.75),
        ('reddit_rss', 'travel', true, 30, 0.75),
        ('reddit_rss', 'lifestyle_home', true, 30, 0.75),
        ('reddit_rss', 'self_improvement', true, 30, 0.75),

        ('youtube_public_pages', 'fitness', true, 40, 0.80),
        ('youtube_public_pages', 'beauty', true, 40, 0.80),
        ('youtube_public_pages', 'food', true, 40, 0.80),
        ('youtube_public_pages', 'study', true, 40, 0.80),
        ('youtube_public_pages', 'fashion', true, 40, 0.80),
        ('youtube_public_pages', 'tech_ai', true, 40, 0.80),
        ('youtube_public_pages', 'finance_crypto', true, 40, 0.80),
        ('youtube_public_pages', 'business_startup', true, 40, 0.80),
        ('youtube_public_pages', 'entertainment', true, 40, 0.80),
        ('youtube_public_pages', 'travel', true, 40, 0.80),
        ('youtube_public_pages', 'lifestyle_home', true, 40, 0.80),
        ('youtube_public_pages', 'self_improvement', true, 40, 0.80),

        ('coingecko_public_api', 'finance_crypto', true, 50, 1.00),
        ('binance_public_api', 'finance_crypto', true, 60, 0.95)
)
INSERT INTO creator_connector_category_map (connector_id, category_id, enabled, priority, weight)
SELECT connector.id, category.id, seed.enabled, seed.priority, seed.weight
FROM connector_categories seed
JOIN creator_source_connectors connector ON connector.code = seed.connector_code
JOIN creator_categories category ON category.code = seed.category_code
ON CONFLICT (connector_id, category_id) DO UPDATE
SET enabled = EXCLUDED.enabled,
    priority = EXCLUDED.priority,
    weight = EXCLUDED.weight,
    updated_at = now();

COMMENT ON TABLE creator_connector_run_diffs IS 'Per-run diff showing incoming source signals versus already stored signals.';
COMMENT ON COLUMN creator_connector_run_diffs.id IS 'Primary key for the connector run diff.';
COMMENT ON COLUMN creator_connector_run_diffs.connector_run_id IS 'Connector run that produced this diff.';
COMMENT ON COLUMN creator_connector_run_diffs.connector_code IS 'Connector code copied for querying and audit.';
COMMENT ON COLUMN creator_connector_run_diffs.target_platform_code IS 'Target publishing platform this diff belongs to.';
COMMENT ON COLUMN creator_connector_run_diffs.category_code IS 'Category this diff belongs to.';
COMMENT ON COLUMN creator_connector_run_diffs.country_code IS 'Country or market context.';
COMMENT ON COLUMN creator_connector_run_diffs.incoming_count IS 'Total structured signals returned by the connector.';
COMMENT ON COLUMN creator_connector_run_diffs.new_count IS 'Signals inserted for the first time.';
COMMENT ON COLUMN creator_connector_run_diffs.duplicate_count IS 'Signals skipped because their dedupe key already existed.';
COMMENT ON COLUMN creator_connector_run_diffs.changed_count IS 'Signals whose existing record changed. Reserved for future deep comparison.';
COMMENT ON COLUMN creator_connector_run_diffs.missing_count IS 'Previously seen signals missing from this run. Reserved for future decay logic.';
COMMENT ON COLUMN creator_connector_run_diffs.diff_payload IS 'JSON detail including new and duplicate dedupe keys.';
COMMENT ON COLUMN creator_connector_run_diffs.created_at IS 'Timestamp when this diff was stored.';
