-- Stable platform language identity stays in language_master; the existing BCP-47 primary key is
-- preserved so model/voice relationships and persisted project data remain valid.
ALTER TABLE language_master ADD COLUMN IF NOT EXISTS platform_code VARCHAR(3);
ALTER TABLE language_master ADD COLUMN IF NOT EXISTS script_code VARCHAR(4);
ALTER TABLE language_master ADD COLUMN IF NOT EXISTS region_code VARCHAR(3);
ALTER TABLE language_master ADD COLUMN IF NOT EXISTS default_language BOOLEAN NOT NULL DEFAULT FALSE;

update language_master
set platform_code = COALESCE(
    CASE language_code
        WHEN 'en-US' THEN 'en' WHEN 'en-GB' THEN 'en'
        WHEN 'hi-IN' THEN 'hi' WHEN 'hi-Latn-IN' THEN 'hi'
        WHEN 'es-ES' THEN 'es' WHEN 'fr-FR' THEN 'fr' WHEN 'de-DE' THEN 'de'
        WHEN 'pt-BR' THEN 'pt' WHEN 'ja-JP' THEN 'ja' WHEN 'zh-CN' THEN 'zh' WHEN 'ar-SA' THEN 'ar'
    END,
    lower(split_part(language_code, '-', 1))
),
    script_code = CASE language_code
        WHEN 'hi-IN' THEN 'Deva' WHEN 'hi-Latn-IN' THEN 'Latn'
        WHEN 'zh-CN' THEN 'Hans' WHEN 'ar-SA' THEN 'Arab' WHEN 'ja-JP' THEN 'Jpan'
        ELSE 'Latn'
    END,
    region_code = CASE language_code
        WHEN 'en-US' THEN 'US' WHEN 'en-GB' THEN 'GB' WHEN 'hi-IN' THEN 'IN' WHEN 'hi-Latn-IN' THEN 'IN'
        WHEN 'es-ES' THEN 'ES' WHEN 'fr-FR' THEN 'FR' WHEN 'de-DE' THEN 'DE' WHEN 'pt-BR' THEN 'BR'
        WHEN 'ja-JP' THEN 'JP' WHEN 'zh-CN' THEN 'CN' WHEN 'ar-SA' THEN 'SA'
    END,
    default_language = language_code IN ('en-US', 'hi-IN', 'es-ES', 'fr-FR', 'de-DE', 'pt-BR', 'ja-JP', 'zh-CN', 'ar-SA');

ALTER TABLE language_master ALTER COLUMN PLATFORM_CODE SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_language_master_platform_code ON language_master(platform_code);

CREATE TABLE provider_language_mapping (
    mapping_id               BIGSERIAL PRIMARY KEY,
    provider_id             VARCHAR(64) NOT NULL REFERENCES provider(provider_id),
    model_id                VARCHAR(128) REFERENCES model_master(model_id),
    language_code           VARCHAR(16) NOT NULL REFERENCES language_master(language_code),
    delivery_mode           VARCHAR(16) NOT NULL,
    provider_parameter_name VARCHAR(64),
    provider_language_code  VARCHAR(64),
    active                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT provider_language_mapping_mode_check CHECK (
        (delivery_mode = 'OMIT' AND provider_parameter_name IS NULL AND provider_language_code IS NULL)
        OR (delivery_mode = 'PARAMETER' AND provider_parameter_name IS NOT NULL AND provider_language_code IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_provider_language_mapping_default
    ON provider_language_mapping(provider_id, language_code) WHERE model_id IS NULL;
CREATE UNIQUE INDEX uq_provider_language_mapping_model
    ON provider_language_mapping(provider_id, model_id, language_code) WHERE model_id IS NOT NULL;

-- ElevenLabs {@code eleven_multilingual_v2} automatically detects language and rejects {@code language_code}.
INSERT INTO provider_language_mapping (provider_id, model_id, language_code, delivery_mode, active)
SELECT 'elevenlabs', 'elevenlabs-tts-v1', language_code, 'OMIT', TRUE
FROM language_master
ON CONFLICT DO NOTHING;
