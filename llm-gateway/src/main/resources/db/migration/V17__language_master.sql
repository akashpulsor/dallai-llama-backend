-- Designed earlier this session, never actually built until now (confirmed missing from the live
-- DB) -- the canonical source of truth for "what languages exist" and "which model supports
-- which," so callers reference a real language_code instead of a free-text string.
CREATE TABLE language_master (
    language_code VARCHAR(16) PRIMARY KEY,   -- BCP-47, e.g. 'en-US', 'hi-IN'
    display_name  VARCHAR(128) NOT NULL,
    native_name   VARCHAR(128),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE model_supported_language (
    model_id      VARCHAR(128) NOT NULL REFERENCES model_master(model_id),
    language_code VARCHAR(16) NOT NULL REFERENCES language_master(language_code),
    PRIMARY KEY (model_id, language_code)
);

INSERT INTO language_master (language_code, display_name, native_name) VALUES
    ('en-US', 'English (US)', 'English'),
    ('en-GB', 'English (UK)', 'English'),
    ('hi-IN', 'Hindi', 'हिन्दी'),
    ('es-ES', 'Spanish', 'Español'),
    ('fr-FR', 'French', 'Français'),
    ('de-DE', 'German', 'Deutsch'),
    ('pt-BR', 'Portuguese (Brazil)', 'Português'),
    ('ja-JP', 'Japanese', '日本語'),
    ('zh-CN', 'Chinese (Simplified)', '中文'),
    ('ar-SA', 'Arabic', 'العربية');

-- Only the two capability types where language is actually a meaningful axis -- lip_sync/video
-- don't have their own language concept (they operate on whatever audio they're given).
INSERT INTO model_supported_language (model_id, language_code)
SELECT 'voice-clone-v1', language_code FROM language_master;

INSERT INTO model_supported_language (model_id, language_code)
SELECT 'tts-v1', language_code FROM language_master;
