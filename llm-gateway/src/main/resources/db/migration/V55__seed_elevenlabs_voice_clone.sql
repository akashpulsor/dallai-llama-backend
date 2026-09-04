-- Real ElevenLabs voice cloning (POST /v1/voices/add) via the direct ElevenLabsProvider, not
-- the fal.ai meta-provider -- a second, selectable cloning option alongside fal.ai's own
-- fal-ai/minimax/voice-clone (V45), not a replacement for it. Cloning alone returns a voice_id
-- (ElevenLabsProvider's own class doc); synthesizing speech in that voice is a separate,
-- already-working type="tts" call against the same provider_id.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('elevenlabs/instant-voice-clone', 'elevenlabs', 'voice_clone', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 60000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('elevenlabs/instant-voice-clone', 0, 0, 'USD', now());

INSERT INTO model_supported_language (model_id, language_code)
SELECT 'elevenlabs/instant-voice-clone', language_code FROM language_master;
