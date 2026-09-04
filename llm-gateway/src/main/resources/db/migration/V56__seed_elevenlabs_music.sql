-- Real ElevenLabs music composition (POST /v1/music) via the direct ElevenLabsProvider --
-- background-music generation for a shot's already-planned sound design, on demand (not part of
-- the automatic dispatch pipeline; see BackgroundMusicService on pre-production-service's side).
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('elevenlabs/music-v1', 'elevenlabs', 'music', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 60000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('elevenlabs/music-v1', 0, 0, 'USD', now());
