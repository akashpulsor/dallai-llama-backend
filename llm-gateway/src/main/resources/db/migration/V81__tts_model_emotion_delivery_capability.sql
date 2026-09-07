-- Data-driven "how does this TTS model want scene emotion conveyed" instead of a Java constant --
-- video-generation-service's SceneEnergyStrategyResolver reads this key (via the existing
-- GET /api/v1/internal/models internal endpoint) to pick the right SceneEnergyStrategy for
-- whichever model a project actually has configured, so adding a differently-capable TTS model
-- later (e.g. one driven by inline text tags instead of numeric settings) needs only a new seed
-- row + a new strategy bean -- no code change to how the choice gets resolved.
UPDATE model_master
SET capabilities = capabilities || '{"emotion_delivery": "numeric_voice_settings"}'::jsonb
WHERE model_id = 'elevenlabs-tts-v1';
