INSERT INTO creator_categories (
    code,
    display_name,
    description,
    icon_key,
    sort_order,
    active,
    visible,
    prompt_context,
    status,
    metadata
)
VALUES (
    'politics',
    'Politics',
    'Elections, public policy, civic explainers, parliament moments, campaign communication, neutral satire, and current affairs commentary.',
    'landmark',
    75,
    true,
    true,
    'Prioritize neutral civic explainers, factual context, non-defamatory commentary, public-interest framing, and clearly labelled satire or opinion.',
    'ACTIVE',
    '{"mvp": true, "llmTrendTags": true}'::jsonb
)
ON CONFLICT (code) DO UPDATE
SET display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    sort_order = EXCLUDED.sort_order,
    active = EXCLUDED.active,
    visible = EXCLUDED.visible,
    prompt_context = EXCLUDED.prompt_context,
    status = EXCLUDED.status,
    metadata = creator_categories.metadata || EXCLUDED.metadata,
    updated_at = now();

WITH target_platforms AS (
    SELECT code
    FROM creator_platforms
    WHERE active = true
      AND target_platform = true
      AND visible = true
),
politics_category AS (
    SELECT code
    FROM creator_categories
    WHERE code = 'politics'
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
    '{"sources": ["llm_weekly_idea_tags"], "apiKeyRequired": true, "schedulerWindowMinutes": 10080}'::jsonb,
    '{"seeded": true, "llmFirst": true}'::jsonb
FROM target_platforms p
CROSS JOIN politics_category c
ON CONFLICT (platform_code, category_code) DO UPDATE
SET enabled = true,
    free_source_policy = EXCLUDED.free_source_policy,
    metadata = creator_trend_combinations.metadata || EXCLUDED.metadata,
    updated_at = now();

WITH keyword_seed(category_code, source_type, locale, include_terms, exclude_terms, weight) AS (
    VALUES
        ('politics', 'llm_weekly_idea_tags', 'en-IN',
         '["Indian politics","elections","parliament","public policy","civic explainer","current affairs","campaign strategy"]'::jsonb,
         '["hate speech","defamation","private person allegation","violence","incitement"]'::jsonb,
         1.00),
        ('politics', 'google_trends', 'en-IN',
         '["Indian politics","election news","parliament session","government policy","current affairs India","political satire"]'::jsonb,
         '["hate speech","defamation","violence","incitement"]'::jsonb,
         0.80),
        ('politics', 'youtube_public_pages', 'en-IN',
         '["politics shorts","current affairs shorts","policy explainer","election explainer","parliament moment"]'::jsonb,
         '["hate speech","defamation","violence","incitement"]'::jsonb,
         0.75)
)
INSERT INTO creator_category_keywords (
    category_id,
    source_type,
    locale,
    include_terms,
    exclude_terms,
    weight,
    active
)
SELECT
    c.id,
    s.source_type,
    s.locale,
    s.include_terms,
    s.exclude_terms,
    s.weight,
    true
FROM keyword_seed s
JOIN creator_categories c ON c.code = s.category_code
ON CONFLICT (category_id, source_type, locale) DO UPDATE
SET include_terms = EXCLUDED.include_terms,
    exclude_terms = EXCLUDED.exclude_terms,
    weight = EXCLUDED.weight,
    active = EXCLUDED.active,
    updated_at = now();

WITH connectors AS (
    SELECT id, code
    FROM creator_source_connectors
    WHERE code IN (
        'mock_trend_source',
        'google_trends_web',
        'reddit_public_json',
        'reddit_rss',
        'youtube_public_pages'
    )
),
politics_category AS (
    SELECT id
    FROM creator_categories
    WHERE code = 'politics'
)
INSERT INTO creator_connector_category_map (
    connector_id,
    category_id,
    enabled,
    priority,
    weight
)
SELECT
    connectors.id,
    politics_category.id,
    CASE WHEN connectors.code = 'mock_trend_source' THEN true ELSE false END,
    CASE WHEN connectors.code = 'mock_trend_source' THEN 1 ELSE 100 END,
    1.00
FROM connectors
CROSS JOIN politics_category
ON CONFLICT (connector_id, category_id) DO UPDATE
SET enabled = EXCLUDED.enabled,
    priority = EXCLUDED.priority,
    weight = EXCLUDED.weight,
    updated_at = now();
