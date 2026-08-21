-- ElevenLabs -- a real, direct provider (ElevenLabsProvider), not behind the fal.ai meta-provider.
-- v1 slice is TTS only; voice cloning is a named fast-follow (see ElevenLabsProvider's own
-- class comment for why: multipart file upload, a genuinely different contract shape).
INSERT INTO provider (provider_id, name, adapter_class, status, account_rpm, account_tpm)
VALUES ('elevenlabs', 'ElevenLabs', 'com.dalai.llama.llmgateway.service.provider.ElevenLabsProvider', 'active', NULL, NULL);

INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('elevenlabs-tts-v1', 'elevenlabs', 'tts', '{}'::jsonb, NULL, false, 'active', 20, 1000000, 30000);

-- Real cost as of this writing is subscription-tier-based (characters/month), not a clean
-- per-call token rate -- 0 here is the same "not yet billable" placeholder as every other
-- non-token-based model seeded so far (V14/V15/V16), not a claim that ElevenLabs is free.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('elevenlabs-tts-v1', 0, 0, 'USD', now());

-- ElevenLabs' multilingual model genuinely supports all of these, unlike the fal.ai seed rows
-- this mirrors the pattern from (which were seeded before any model was confirmed to work at all).
INSERT INTO model_supported_language (model_id, language_code)
SELECT 'elevenlabs-tts-v1', language_code FROM language_master;
