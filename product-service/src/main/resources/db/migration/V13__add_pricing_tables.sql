-- ============================================================================
-- V2__add_pricing_tables.sql
-- Flyway migration for pricing model
-- Place in: src/main/resources/db/migration/
-- ============================================================================

-- 1. Add new columns to plans table
ALTER TABLE plans ADD COLUMN IF NOT EXISTS per_agent_fee DECIMAL(10,2) DEFAULT 0;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS setup_fee DECIMAL(10,2) DEFAULT 0;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS included_minutes INT DEFAULT 0;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS included_agents INT DEFAULT 0;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS ai_stack_type VARCHAR(20);
ALTER TABLE plans ADD COLUMN IF NOT EXISTS ai_rate_per_min DECIMAL(6,2);

-- 2. Create ai_providers table
CREATE TABLE IF NOT EXISTS ai_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_type VARCHAR(10) NOT NULL CHECK (provider_type IN ('STT', 'TTS', 'LLM')),
    provider_name VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    cost_per_min DECIMAL(8,4) NOT NULL,
    cost_per_1k_tokens DECIMAL(8,4),
    currency VARCHAR(3) DEFAULT 'INR',
    quality_tier VARCHAR(20) CHECK (quality_tier IN ('DALAI_LLAMA', 'BUDGET', 'STANDARD', 'PREMIUM')),
    is_dalai_llama BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    icon_url VARCHAR(500),
    icon_svg TEXT,
    brand_color VARCHAR(7),
    languages_supported JSONB,
    supports_streaming BOOLEAN DEFAULT true,
    supports_realtime BOOLEAN DEFAULT false,
    avg_latency_ms INT,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider_type, provider_name, model_name)
);

CREATE INDEX IF NOT EXISTS idx_ai_providers_type ON ai_providers(provider_type);
CREATE INDEX IF NOT EXISTS idx_ai_providers_tier ON ai_providers(quality_tier);
CREATE INDEX IF NOT EXISTS idx_ai_providers_active ON ai_providers(is_active);

-- 3. Create plan_ai_configs table
CREATE TABLE IF NOT EXISTS plan_ai_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    stt_provider_id UUID REFERENCES ai_providers(id),
    stt_cost_per_min DECIMAL(8,4),
    tts_provider_id UUID REFERENCES ai_providers(id),
    tts_cost_per_min DECIMAL(8,4),
    llm_provider_id UUID REFERENCES ai_providers(id),
    llm_cost_per_min DECIMAL(8,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0,
    UNIQUE(plan_id)
);

CREATE INDEX IF NOT EXISTS idx_plan_ai_configs_plan ON plan_ai_configs(plan_id);

-- 4. Seed AI Providers
INSERT INTO ai_providers (provider_type, provider_name, model_name, display_name, cost_per_min, quality_tier, icon_url, brand_color, avg_latency_ms) VALUES
-- BUDGET tier
('STT', 'Groq', 'whisper-large-v3', 'Groq Whisper', 0.10, 'BUDGET', 'https://groq.com/favicon.ico', '#F55036', 50),
('TTS', 'Google', 'standard', 'Google Standard', 0.40, 'BUDGET', 'https://www.gstatic.com/images/branding/product/1x/googleg_48dp.png', '#4285F4', 80),
('LLM', 'Groq', 'llama-3.1-70b-versatile', 'Groq Llama 70B', 0.15, 'BUDGET', 'https://groq.com/favicon.ico', '#F55036', 50),
-- STANDARD tier
('STT', 'Deepgram', 'nova-2', 'Deepgram Nova-2', 0.50, 'STANDARD', 'https://deepgram.com/favicon.ico', '#13EF93', 100),
('TTS', 'OpenAI', 'tts-1', 'OpenAI TTS', 1.25, 'STANDARD', 'https://openai.com/favicon.ico', '#000000', 120),
('LLM', 'OpenAI', 'gpt-4o-mini', 'GPT-4o Mini', 0.80, 'STANDARD', 'https://openai.com/favicon.ico', '#000000', 150),
('TTS', 'Google', 'wavenet', 'Google WaveNet', 1.00, 'STANDARD', 'https://www.gstatic.com/images/branding/product/1x/googleg_48dp.png', '#4285F4', 100),
-- PREMIUM tier
('STT', 'Deepgram', 'nova-2-premium', 'Deepgram Nova-2', 0.50, 'PREMIUM', 'https://deepgram.com/favicon.ico', '#13EF93', 100),
('TTS', 'ElevenLabs', 'eleven_multilingual_v2', 'ElevenLabs Multilingual', 2.50, 'PREMIUM', 'https://elevenlabs.io/favicon.ico', '#000000', 200),
('LLM', 'OpenAI', 'gpt-4o', 'GPT-4o', 4.00, 'PREMIUM', 'https://openai.com/favicon.ico', '#000000', 200)
ON CONFLICT (provider_type, provider_name, model_name) DO UPDATE SET
    cost_per_min = EXCLUDED.cost_per_min,
    display_name = EXCLUDED.display_name,
    icon_url = EXCLUDED.icon_url,
    brand_color = EXCLUDED.brand_color,
    avg_latency_ms = EXCLUDED.avg_latency_ms,
    updated_at = CURRENT_TIMESTAMP;

-- 5. Update existing plans with pricing info (AI_CC example)
UPDATE plans SET
    per_agent_fee = 699,
    included_agents = 3,
    included_minutes = 3000,
    ai_stack_type = 'BUDGET',
    ai_rate_per_min = 4.00
WHERE code LIKE 'AI_CC%' AND tier = 'STARTER';

UPDATE plans SET
    per_agent_fee = 699,
    included_agents = 5,
    included_minutes = 3000,
    ai_stack_type = 'STANDARD',
    ai_rate_per_min = 6.00
WHERE code LIKE 'AI_CC%' AND tier = 'PROFESSIONAL';

UPDATE plans SET
    per_agent_fee = 599,
    included_agents = 10,
    included_minutes = 5000,
    ai_stack_type = 'PREMIUM',
    ai_rate_per_min = 12.00
WHERE code LIKE 'AI_CC%' AND tier = 'ENTERPRISE';

-- 6. Link plans to AI providers via plan_ai_configs
-- This creates the AI stack configuration for each plan
INSERT INTO plan_ai_configs (plan_id, stt_provider_id, stt_cost_per_min, tts_provider_id, tts_cost_per_min, llm_provider_id, llm_cost_per_min)
SELECT
    p.id,
    stt.id, stt.cost_per_min,
    tts.id, tts.cost_per_min,
    llm.id, llm.cost_per_min
FROM plans p
CROSS JOIN LATERAL (
    SELECT id, cost_per_min FROM ai_providers
    WHERE provider_type = 'STT' AND quality_tier = p.ai_stack_type::varchar AND is_active = true
    LIMIT 1
) stt
CROSS JOIN LATERAL (
    SELECT id, cost_per_min FROM ai_providers
    WHERE provider_type = 'TTS' AND quality_tier = p.ai_stack_type::varchar AND is_active = true
    LIMIT 1
) tts
CROSS JOIN LATERAL (
    SELECT id, cost_per_min FROM ai_providers
    WHERE provider_type = 'LLM' AND quality_tier = p.ai_stack_type::varchar AND is_active = true
    LIMIT 1
) llm
WHERE p.ai_stack_type IS NOT NULL
ON CONFLICT (plan_id) DO UPDATE SET
    stt_provider_id = EXCLUDED.stt_provider_id,
    stt_cost_per_min = EXCLUDED.stt_cost_per_min,
    tts_provider_id = EXCLUDED.tts_provider_id,
    tts_cost_per_min = EXCLUDED.tts_cost_per_min,
    llm_provider_id = EXCLUDED.llm_provider_id,
    llm_cost_per_min = EXCLUDED.llm_cost_per_min,
    updated_at = CURRENT_TIMESTAMP;