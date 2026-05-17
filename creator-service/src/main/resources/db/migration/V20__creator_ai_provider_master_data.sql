CREATE TABLE IF NOT EXISTS creator_ai_providers (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    description TEXT,
    provider_type VARCHAR(64) NOT NULL,
    default_model VARCHAR(120) NOT NULL,
    api_base_url VARCHAR(240),
    credential_env_key VARCHAR(120),
    sort_order INTEGER NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    default_provider BOOLEAN NOT NULL DEFAULT FALSE,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creator_ai_providers_active_visible
    ON creator_ai_providers(active, visible, sort_order);

INSERT INTO creator_ai_providers (
    id,
    code,
    display_name,
    description,
    provider_type,
    default_model,
    api_base_url,
    credential_env_key,
    sort_order,
    active,
    visible,
    default_provider,
    capabilities,
    metadata
) VALUES
    (
        gen_random_uuid(),
        'openai',
        'OpenAI',
        'OpenAI Responses API provider for production creator generation.',
        'LLM',
        'gpt-4o-mini',
        'https://api.openai.com/v1',
        'OPENAI_API_KEY',
        10,
        true,
        true,
        true,
        '{"textGeneration": true, "jsonOutput": true, "usageTokens": true}'::jsonb,
        '{"billingCurrency": "INR"}'::jsonb
    ),
    (
        gen_random_uuid(),
        'mock',
        'Mock Provider',
        'Deterministic local provider for development and tests.',
        'MOCK',
        'mock-creator-v1',
        null,
        null,
        90,
        true,
        true,
        false,
        '{"textGeneration": true, "jsonOutput": true, "offline": true}'::jsonb,
        '{}'::jsonb
    )
ON CONFLICT (code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    provider_type = EXCLUDED.provider_type,
    default_model = EXCLUDED.default_model,
    api_base_url = EXCLUDED.api_base_url,
    credential_env_key = EXCLUDED.credential_env_key,
    sort_order = EXCLUDED.sort_order,
    active = EXCLUDED.active,
    visible = EXCLUDED.visible,
    default_provider = EXCLUDED.default_provider,
    capabilities = EXCLUDED.capabilities,
    metadata = EXCLUDED.metadata,
    updated_at = now();
