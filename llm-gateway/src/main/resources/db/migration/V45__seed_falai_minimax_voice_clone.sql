-- Real voice cloning via fal.ai's own hosted MiniMax model -- ElevenLabs does not expose a
-- dedicated instant-voice-clone endpoint on fal.ai (only TTS, voice-changer, dubbing, music,
-- scribe), and this system is standardized on accessing everything through the fal.ai
-- meta-provider, not a separate direct-to-ElevenLabs provider. Verified against fal.ai's real,
-- documented fal-ai/minimax/voice-clone schema (audio_url in; custom_voice_id out, or audio.url
-- out when a text param is also given -- see FalAiProvider's voice_clone handling).
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('fal-ai/minimax/voice-clone', 'fal.ai', 'voice_clone', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 60000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('fal-ai/minimax/voice-clone', 0, 0, 'USD', now());

INSERT INTO model_supported_language (model_id, language_code)
SELECT 'fal-ai/minimax/voice-clone', language_code FROM language_master;
