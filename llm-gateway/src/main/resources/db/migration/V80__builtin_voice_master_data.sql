-- Stock (non-cloned) ElevenLabs voices for a character with no actor voice sample to clone -- see
-- CastProfile.builtinVoiceId on pre-production-service's side. Same shape as language_master /
-- model_supported_language (V17): a real master table + a many-to-many join to language_master,
-- not a hardcoded frontend list, since which languages a stock voice covers is real data that can
-- change independently of code.
CREATE TABLE builtin_voice (
    voice_id           VARCHAR(64) PRIMARY KEY,
    provider_id        VARCHAR(64) NOT NULL REFERENCES provider(provider_id),
    provider_voice_id  VARCHAR(128) NOT NULL,
    display_name       VARCHAR(128) NOT NULL,
    gender             VARCHAR(16) NOT NULL CHECK (gender IN ('MALE', 'FEMALE')),
    preview_audio_url  TEXT,
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_builtin_voice_active_gender ON builtin_voice (active, gender);

CREATE TABLE builtin_voice_language (
    voice_id       VARCHAR(64) NOT NULL REFERENCES builtin_voice(voice_id),
    language_code  VARCHAR(16) NOT NULL REFERENCES language_master(language_code),
    PRIMARY KEY (voice_id, language_code)
);

-- Pulled live from this account's GET /v2/voices (voices_read permission granted 2026-09-07) --
-- real voice_ids and preview URLs, not guessed. All four are ElevenLabs "premade" voices with
-- hi + en verified in their own verified_languages (i.e. explicitly quality-checked for both, not
-- just theoretically reachable via the multilingual model), covering the confirmed English +
-- Hindi scope with one male + one female pair to spare.
INSERT INTO builtin_voice (voice_id, provider_id, provider_voice_id, display_name, gender, preview_audio_url, active) VALUES
    ('elevenlabs-sarah',  'elevenlabs', 'EXAVITQu4vr4xnSDxMaL', 'Sarah - Mature, Reassuring, Confident',  'FEMALE',
        'https://storage.googleapis.com/eleven-public-prod/premade/voices/EXAVITQu4vr4xnSDxMaL/01a3e33c-6e99-4ee7-8543-ff2216a32186.mp3', TRUE),
    ('elevenlabs-alice',  'elevenlabs', 'Xb7hH8MSUJpSbSDYk0k2', 'Alice - Clear, Engaging Educator',        'FEMALE',
        'https://storage.googleapis.com/eleven-public-prod/premade/voices/Xb7hH8MSUJpSbSDYk0k2/d10f7534-11f6-41fe-a012-2de1e482d336.mp3', TRUE),
    ('elevenlabs-george', 'elevenlabs', 'JBFqnCBsd6RMkjVDRZzb', 'George - Warm, Captivating Storyteller',  'MALE',
        'https://api.us.elevenlabs.io/v1/voices/JBFqnCBsd6RMkjVDRZzb/previews/audio?payload=eyJ2b2ljZV9zb3VyY2UiOiJwcmVtYWRlIiwiZmlsZW5hbWUiOiJlNjIwNmQxYS0wNzIxLTQ3ODctYWFmYi0wNmE2ZTcwNWNhYzUubXAzIiwidGltZXN0YW1wIjoxNzg4Nzg5NjAwMDAwMDAwfQ%3D%3D', TRUE),
    ('elevenlabs-brian',  'elevenlabs', 'nPczCjzI2devNBz1zQrb', 'Brian - Deep, Resonant and Comforting',   'MALE',
        'https://api.us.elevenlabs.io/v1/voices/nPczCjzI2devNBz1zQrb/previews/audio?payload=eyJ2b2ljZV9zb3VyY2UiOiJwcmVtYWRlIiwiZmlsZW5hbWUiOiIyZGQzZTcyYy00ZmQzLTQyZjEtOTNlYS1hYmM1ZDRlNWFhMWQubXAzIiwidGltZXN0YW1wIjoxNzg4Nzg5NjAwMDAwMDAwfQ%3D%3D', TRUE);

INSERT INTO builtin_voice_language (voice_id, language_code)
SELECT voice_id, lang
FROM builtin_voice CROSS JOIN (VALUES ('en-US'), ('hi-IN')) AS languages(lang)
WHERE voice_id IN ('elevenlabs-sarah', 'elevenlabs-alice', 'elevenlabs-george', 'elevenlabs-brian');
