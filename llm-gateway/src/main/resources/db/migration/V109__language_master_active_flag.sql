-- Adds an `active` gate on language_master and deactivates the languages that were seeded in V17
-- as placeholders but aren't actually supported by any voice/TTS model this platform runs
-- (single-client, India-focused deployment -- see the "single client, manual ops" memory).
--
-- Kept as a boolean rather than a delete because model_supported_language, builtin_voice_language,
-- and every project_config.dialogue_language reference these rows via FK. A UPDATE preserves that
-- history while the read path (listLanguages) simply excludes anything with active=false so
-- creators only see codes we can actually render.
ALTER TABLE language_master
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;

-- Deactivate the placeholder rows. English-US, Hindi (Devanagari), and Hinglish (Latin-script
-- Hindi) are the three we support end-to-end today. Add more back by flipping active=true when
-- a real voice model becomes available for them.
UPDATE language_master SET active = false
WHERE language_code IN (
    'en-GB',   -- no distinct UK voice; en-US covers English
    'es-ES', 'fr-FR', 'de-DE', 'pt-BR', 'ja-JP', 'zh-CN', 'ar-SA'
);
