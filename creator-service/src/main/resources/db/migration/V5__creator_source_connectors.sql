CREATE TABLE IF NOT EXISTS creator_source_connectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(96) NOT NULL UNIQUE,
    display_name VARCHAR(180) NOT NULL,
    source_platform_code VARCHAR(64) NOT NULL,
    call_method VARCHAR(64) NOT NULL,
    auth_type VARCHAR(64) NOT NULL DEFAULT 'NONE',
    parser_type VARCHAR(64) NOT NULL DEFAULT 'JSON',
    base_url TEXT,
    request_template JSONB NOT NULL DEFAULT '{}'::jsonb,
    headers_template JSONB NOT NULL DEFAULT '{}'::jsonb,
    rate_limit_per_minute INTEGER NOT NULL DEFAULT 10,
    rate_limit_per_day INTEGER,
    monthly_call_limit INTEGER,
    timeout_ms INTEGER NOT NULL DEFAULT 10000,
    retry_count INTEGER NOT NULL DEFAULT 2,
    retry_backoff_ms INTEGER NOT NULL DEFAULT 1000,
    failure_policy VARCHAR(64) NOT NULL DEFAULT 'CONTINUE',
    circuit_breaker_failure_threshold INTEGER NOT NULL DEFAULT 5,
    cooldown_seconds INTEGER NOT NULL DEFAULT 900,
    enabled BOOLEAN NOT NULL DEFAULT true,
    requires_api_key BOOLEAN NOT NULL DEFAULT false,
    priority INTEGER NOT NULL DEFAULT 100,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS creator_connector_category_map (
    connector_id UUID NOT NULL REFERENCES creator_source_connectors(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES creator_categories(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    priority INTEGER NOT NULL DEFAULT 100,
    weight NUMERIC(5, 2) NOT NULL DEFAULT 1.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, category_id)
);

CREATE TABLE IF NOT EXISTS creator_connector_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_id UUID REFERENCES creator_source_connectors(id) ON DELETE SET NULL,
    connector_code VARCHAR(96) NOT NULL,
    target_platform_code VARCHAR(64) NOT NULL,
    source_platform_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    country_code VARCHAR(16) NOT NULL DEFAULT 'IN',
    window_started_at TIMESTAMPTZ NOT NULL,
    window_ended_at TIMESTAMPTZ NOT NULL,
    call_method VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    request_count INTEGER NOT NULL DEFAULT 0,
    items_found INTEGER NOT NULL DEFAULT 0,
    http_status INTEGER,
    error_code VARCHAR(120),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    request_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    structured_payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS creator_trend_signals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connector_run_id UUID REFERENCES creator_connector_runs(id) ON DELETE SET NULL,
    connector_code VARCHAR(96) NOT NULL,
    source_platform_code VARCHAR(64) NOT NULL,
    target_platform_code VARCHAR(64) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    country_code VARCHAR(16) NOT NULL DEFAULT 'IN',
    source_type VARCHAR(80) NOT NULL,
    source_url TEXT,
    title VARCHAR(280) NOT NULL,
    summary TEXT,
    signal_text TEXT,
    source_author VARCHAR(180),
    engagement_score NUMERIC(12, 2) NOT NULL DEFAULT 0,
    rank_score NUMERIC(12, 2) NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_item JSONB NOT NULL DEFAULT '{}'::jsonb,
    normalized_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creator_trend_signal_dedupe UNIQUE (connector_code, dedupe_key)
);

CREATE INDEX IF NOT EXISTS idx_creator_source_connectors_enabled
    ON creator_source_connectors (enabled, priority, source_platform_code);

CREATE INDEX IF NOT EXISTS idx_creator_connector_category_map_category
    ON creator_connector_category_map (category_id, enabled, priority);

CREATE INDEX IF NOT EXISTS idx_creator_connector_runs_lookup
    ON creator_connector_runs (connector_code, target_platform_code, category_code, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_trend_signals_lookup
    ON creator_trend_signals (target_platform_code, category_code, country_code, observed_at DESC);

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
    ('mock', 'Mock Source', 'Local deterministic source used for scheduler and connector tests.', 'test-tube', 990, true, false, false, true, false, 'Use only for local deterministic trend signal generation.', 'ACTIVE', '{"signalSource": true, "mock": true}'::jsonb),
    ('coingecko', 'CoinGecko', 'No-key public crypto market signal source.', 'coins', 930, true, false, false, true, false, 'Use as crypto market trend signal only.', 'ACTIVE', '{"signalSource": true}'::jsonb),
    ('binance_public', 'Binance Public API', 'No-key public crypto market price and volume signal source.', 'chart-candlestick', 940, true, false, false, true, false, 'Use as crypto market trend signal only.', 'ACTIVE', '{"signalSource": true}'::jsonb)
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

INSERT INTO creator_source_connectors (
    code,
    display_name,
    source_platform_code,
    call_method,
    auth_type,
    parser_type,
    base_url,
    request_template,
    headers_template,
    rate_limit_per_minute,
    rate_limit_per_day,
    monthly_call_limit,
    timeout_ms,
    retry_count,
    retry_backoff_ms,
    failure_policy,
    enabled,
    requires_api_key,
    priority,
    config_json
)
VALUES
    ('mock_trend_source', 'Mock Trend Source', 'mock', 'MOCK', 'NONE', 'JSON', NULL,
     '{"query": "{{includeTerms}}", "locale": "{{locale}}"}'::jsonb,
     '{}'::jsonb,
     120, 10000, 300000, 1000, 0, 0, 'CONTINUE', true, false, 1,
     '{"purpose": "deterministic scheduler and local testing"}'::jsonb),
    ('google_trends_web', 'Google Trends Web', 'google_trends', 'WEB_REQUEST', 'NONE', 'HTML', 'https://trends.google.com',
     '{"path": "/trends/explore", "query": {"q": "{{query}}", "geo": "{{countryCode}}"}}'::jsonb,
     '{"User-Agent": "DalaiLlamaCreatorBot/1.0"}'::jsonb,
     5, 250, 5000, 12000, 2, 2000, 'CONTINUE', false, false, 10,
     '{"mvpStatus": "disabled_until_parser_added"}'::jsonb),
    ('reddit_public_json', 'Reddit Public JSON', 'reddit', 'PUBLIC_API_NO_KEY', 'NONE', 'JSON', 'https://www.reddit.com',
     '{"path": "/search.json", "query": {"q": "{{query}}", "sort": "hot", "t": "day", "limit": 25}}'::jsonb,
     '{"User-Agent": "DalaiLlamaCreatorBot/1.0"}'::jsonb,
     60, 5000, 100000, 10000, 2, 1500, 'CONTINUE', false, false, 20,
     '{"mvpStatus": "disabled_until_parser_added"}'::jsonb),
    ('reddit_rss', 'Reddit RSS', 'reddit', 'RSS_FEED', 'NONE', 'RSS', 'https://www.reddit.com',
     '{"path": "/search.rss", "query": {"q": "{{query}}", "sort": "hot", "t": "day"}}'::jsonb,
     '{"User-Agent": "DalaiLlamaCreatorBot/1.0"}'::jsonb,
     60, 5000, 100000, 10000, 2, 1500, 'CONTINUE', false, false, 30,
     '{"mvpStatus": "disabled_until_parser_added"}'::jsonb),
    ('youtube_public_pages', 'YouTube Public Pages', 'youtube_public_pages', 'WEB_REQUEST', 'NONE', 'HTML', 'https://www.youtube.com',
     '{"path": "/results", "query": {"search_query": "{{query}} shorts"}}'::jsonb,
     '{"User-Agent": "DalaiLlamaCreatorBot/1.0"}'::jsonb,
     10, 1000, 25000, 12000, 2, 2000, 'CONTINUE', false, false, 40,
     '{"mvpStatus": "disabled_until_parser_added"}'::jsonb),
    ('coingecko_public_api', 'CoinGecko Public API', 'coingecko', 'PUBLIC_API_NO_KEY', 'NONE', 'JSON', 'https://api.coingecko.com/api/v3',
     '{"path": "/search/trending"}'::jsonb,
     '{}'::jsonb,
     10, 500, 10000, 10000, 2, 2000, 'CONTINUE', false, false, 50,
     '{"categoryCodes": ["finance_crypto"], "mvpStatus": "disabled_until_parser_added"}'::jsonb),
    ('binance_public_api', 'Binance Public API', 'binance_public', 'PUBLIC_API_NO_KEY', 'NONE', 'JSON', 'https://api.binance.com',
     '{"path": "/api/v3/ticker/24hr"}'::jsonb,
     '{}'::jsonb,
     1200, 50000, 1000000, 10000, 2, 1000, 'CONTINUE', false, false, 60,
     '{"categoryCodes": ["finance_crypto"], "mvpStatus": "disabled_until_parser_added"}'::jsonb)
ON CONFLICT (code) DO UPDATE
SET display_name = EXCLUDED.display_name,
    source_platform_code = EXCLUDED.source_platform_code,
    call_method = EXCLUDED.call_method,
    auth_type = EXCLUDED.auth_type,
    parser_type = EXCLUDED.parser_type,
    base_url = EXCLUDED.base_url,
    request_template = EXCLUDED.request_template,
    headers_template = EXCLUDED.headers_template,
    rate_limit_per_minute = EXCLUDED.rate_limit_per_minute,
    rate_limit_per_day = EXCLUDED.rate_limit_per_day,
    monthly_call_limit = EXCLUDED.monthly_call_limit,
    timeout_ms = EXCLUDED.timeout_ms,
    retry_count = EXCLUDED.retry_count,
    retry_backoff_ms = EXCLUDED.retry_backoff_ms,
    failure_policy = EXCLUDED.failure_policy,
    enabled = EXCLUDED.enabled,
    requires_api_key = EXCLUDED.requires_api_key,
    priority = EXCLUDED.priority,
    config_json = creator_source_connectors.config_json || EXCLUDED.config_json,
    updated_at = now();

WITH enabled_connector AS (
    SELECT id FROM creator_source_connectors WHERE code = 'mock_trend_source'
),
visible_categories AS (
    SELECT id FROM creator_categories WHERE active = true AND visible = true
)
INSERT INTO creator_connector_category_map (connector_id, category_id, enabled, priority, weight)
SELECT enabled_connector.id, visible_categories.id, true, 1, 1.00
FROM enabled_connector
CROSS JOIN visible_categories
ON CONFLICT (connector_id, category_id) DO UPDATE
SET enabled = EXCLUDED.enabled,
    priority = EXCLUDED.priority,
    weight = EXCLUDED.weight,
    updated_at = now();

WITH disabled_connectors AS (
    SELECT id
    FROM creator_source_connectors
    WHERE code IN (
        'google_trends_web',
        'reddit_public_json',
        'reddit_rss',
        'youtube_public_pages',
        'coingecko_public_api',
        'binance_public_api'
    )
),
visible_categories AS (
    SELECT id FROM creator_categories WHERE active = true AND visible = true
)
INSERT INTO creator_connector_category_map (connector_id, category_id, enabled, priority, weight)
SELECT disabled_connectors.id, visible_categories.id, false, 100, 1.00
FROM disabled_connectors
CROSS JOIN visible_categories
ON CONFLICT (connector_id, category_id) DO UPDATE
SET priority = EXCLUDED.priority,
    weight = EXCLUDED.weight,
    updated_at = now();

COMMENT ON TABLE creator_source_connectors IS 'Master data defining how the scheduler calls each no-key or future paid trend source.';
COMMENT ON COLUMN creator_source_connectors.id IS 'Primary key for the source connector.';
COMMENT ON COLUMN creator_source_connectors.code IS 'Stable connector code used by scheduler and run logs.';
COMMENT ON COLUMN creator_source_connectors.display_name IS 'Human-readable connector name.';
COMMENT ON COLUMN creator_source_connectors.source_platform_code IS 'Signal source platform code, for example reddit or google_trends.';
COMMENT ON COLUMN creator_source_connectors.call_method IS 'How the connector is called, for example WEB_REQUEST, PUBLIC_API_NO_KEY, RSS_FEED, OFFICIAL_API_KEY, OFFICIAL_SDK, INTERNAL_SERVICE, or MOCK.';
COMMENT ON COLUMN creator_source_connectors.auth_type IS 'Authentication style required by the connector, for example NONE, API_KEY, OAUTH2, or SESSION_COOKIE.';
COMMENT ON COLUMN creator_source_connectors.parser_type IS 'Parser needed for the response, for example JSON, HTML, RSS, TEXT, or CUSTOM.';
COMMENT ON COLUMN creator_source_connectors.base_url IS 'Base URL for network-based connectors.';
COMMENT ON COLUMN creator_source_connectors.request_template IS 'JSON request template rendered with category, locale, country, and keyword context.';
COMMENT ON COLUMN creator_source_connectors.headers_template IS 'JSON header template rendered before making a request.';
COMMENT ON COLUMN creator_source_connectors.rate_limit_per_minute IS 'Maximum connector calls allowed per minute.';
COMMENT ON COLUMN creator_source_connectors.rate_limit_per_day IS 'Maximum connector calls allowed per day.';
COMMENT ON COLUMN creator_source_connectors.monthly_call_limit IS 'Maximum connector calls allowed per month.';
COMMENT ON COLUMN creator_source_connectors.timeout_ms IS 'Per-call timeout in milliseconds.';
COMMENT ON COLUMN creator_source_connectors.retry_count IS 'Number of retries after the first failed attempt.';
COMMENT ON COLUMN creator_source_connectors.retry_backoff_ms IS 'Delay between retries in milliseconds.';
COMMENT ON COLUMN creator_source_connectors.failure_policy IS 'How scheduler handles failure, for example CONTINUE, SKIP_CATEGORY, DISABLE_CONNECTOR, or FAIL_RUN.';
COMMENT ON COLUMN creator_source_connectors.circuit_breaker_failure_threshold IS 'Consecutive failure threshold before temporarily cooling down this connector.';
COMMENT ON COLUMN creator_source_connectors.cooldown_seconds IS 'Cooldown duration after circuit breaker trips.';
COMMENT ON COLUMN creator_source_connectors.enabled IS 'Whether scheduler is allowed to run this connector.';
COMMENT ON COLUMN creator_source_connectors.requires_api_key IS 'Whether this connector requires a configured secret/API key.';
COMMENT ON COLUMN creator_source_connectors.priority IS 'Execution order among enabled connectors.';
COMMENT ON COLUMN creator_source_connectors.config_json IS 'Connector-specific settings that do not deserve first-class columns yet.';
COMMENT ON COLUMN creator_source_connectors.created_at IS 'Timestamp when the connector was created.';
COMMENT ON COLUMN creator_source_connectors.updated_at IS 'Timestamp when the connector was last updated.';

COMMENT ON TABLE creator_connector_category_map IS 'Mapping of connectors to categories they can collect signals for.';
COMMENT ON COLUMN creator_connector_category_map.connector_id IS 'Connector allowed to collect for the category.';
COMMENT ON COLUMN creator_connector_category_map.category_id IS 'Category the connector can collect for.';
COMMENT ON COLUMN creator_connector_category_map.enabled IS 'Whether this connector/category mapping is active.';
COMMENT ON COLUMN creator_connector_category_map.priority IS 'Execution order for this connector within the category.';
COMMENT ON COLUMN creator_connector_category_map.weight IS 'Relative source weight for ranking/classification.';
COMMENT ON COLUMN creator_connector_category_map.created_at IS 'Timestamp when the mapping was created.';
COMMENT ON COLUMN creator_connector_category_map.updated_at IS 'Timestamp when the mapping was last updated.';

COMMENT ON TABLE creator_connector_runs IS 'Audit table for every scheduler attempt to run one connector for one target platform/category/country/window.';
COMMENT ON COLUMN creator_connector_runs.id IS 'Primary key for the connector run.';
COMMENT ON COLUMN creator_connector_runs.connector_id IS 'Connector row used for this run.';
COMMENT ON COLUMN creator_connector_runs.connector_code IS 'Connector code copied for audit stability.';
COMMENT ON COLUMN creator_connector_runs.target_platform_code IS 'Target publishing platform this run is collecting signals for.';
COMMENT ON COLUMN creator_connector_runs.source_platform_code IS 'Signal source platform used by this connector.';
COMMENT ON COLUMN creator_connector_runs.category_code IS 'Category this connector run is collecting for.';
COMMENT ON COLUMN creator_connector_runs.country_code IS 'Country or market context.';
COMMENT ON COLUMN creator_connector_runs.window_started_at IS 'Start of scheduler collection window.';
COMMENT ON COLUMN creator_connector_runs.window_ended_at IS 'End of scheduler collection window.';
COMMENT ON COLUMN creator_connector_runs.call_method IS 'Call method used for this run.';
COMMENT ON COLUMN creator_connector_runs.status IS 'Run status such as PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, or RATE_LIMITED.';
COMMENT ON COLUMN creator_connector_runs.attempt_count IS 'Number of attempts made including retries.';
COMMENT ON COLUMN creator_connector_runs.request_count IS 'Number of upstream requests made.';
COMMENT ON COLUMN creator_connector_runs.items_found IS 'Number of structured signal items found.';
COMMENT ON COLUMN creator_connector_runs.http_status IS 'Last HTTP status, when applicable.';
COMMENT ON COLUMN creator_connector_runs.error_code IS 'Machine-readable failure code.';
COMMENT ON COLUMN creator_connector_runs.error_message IS 'Human-readable failure detail.';
COMMENT ON COLUMN creator_connector_runs.started_at IS 'Timestamp when the connector run started.';
COMMENT ON COLUMN creator_connector_runs.completed_at IS 'Timestamp when the connector run completed or failed.';
COMMENT ON COLUMN creator_connector_runs.request_snapshot IS 'Rendered request details used for this run.';
COMMENT ON COLUMN creator_connector_runs.response_snapshot IS 'Small response summary or parse metadata. Large raw data should be stored in object storage later.';
COMMENT ON COLUMN creator_connector_runs.structured_payload IS 'Structured connector output summary stored for debugging and replay.';

COMMENT ON TABLE creator_trend_signals IS 'Structured, normalized source items produced by connector runs before trend ranking/prediction.';
COMMENT ON COLUMN creator_trend_signals.id IS 'Primary key for the trend signal.';
COMMENT ON COLUMN creator_trend_signals.connector_run_id IS 'Connector run that produced this signal.';
COMMENT ON COLUMN creator_trend_signals.connector_code IS 'Connector code copied for querying and audit.';
COMMENT ON COLUMN creator_trend_signals.source_platform_code IS 'Signal source platform, for example reddit.';
COMMENT ON COLUMN creator_trend_signals.target_platform_code IS 'Target publishing platform the signal will inform.';
COMMENT ON COLUMN creator_trend_signals.category_code IS 'Category assigned to this signal.';
COMMENT ON COLUMN creator_trend_signals.country_code IS 'Country or market context.';
COMMENT ON COLUMN creator_trend_signals.source_type IS 'Source type/call method used to create the signal.';
COMMENT ON COLUMN creator_trend_signals.source_url IS 'Source URL for this signal, when available.';
COMMENT ON COLUMN creator_trend_signals.title IS 'Structured signal title.';
COMMENT ON COLUMN creator_trend_signals.summary IS 'Structured signal summary.';
COMMENT ON COLUMN creator_trend_signals.signal_text IS 'Main searchable/classifiable text for the signal.';
COMMENT ON COLUMN creator_trend_signals.source_author IS 'Author/channel/community/source identity, when available.';
COMMENT ON COLUMN creator_trend_signals.engagement_score IS 'Source-specific engagement metric normalized into a number.';
COMMENT ON COLUMN creator_trend_signals.rank_score IS 'Connector-side score before global trend ranking.';
COMMENT ON COLUMN creator_trend_signals.published_at IS 'Original source publish timestamp, when available.';
COMMENT ON COLUMN creator_trend_signals.observed_at IS 'Timestamp when our scheduler observed this signal.';
COMMENT ON COLUMN creator_trend_signals.raw_item IS 'Per-item raw source payload.';
COMMENT ON COLUMN creator_trend_signals.normalized_payload IS 'Per-item normalized metadata used by ranking and analysis.';
COMMENT ON COLUMN creator_trend_signals.dedupe_key IS 'Stable key used to avoid storing the same source signal repeatedly.';
COMMENT ON COLUMN creator_trend_signals.created_at IS 'Timestamp when this structured signal was stored.';
