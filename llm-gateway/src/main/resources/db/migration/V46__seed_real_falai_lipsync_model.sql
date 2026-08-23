-- wav2lip-v1 (V15) is not a real fal.ai app slug -- FalAiProvider.falEndpoint() posts directly to
-- "/" + model_id for non-video types, so a fake slug 404s. Verified against fal.ai's real,
-- documented API (fal-ai/sync-lipsync: input {video_url, audio_url, model?, sync_mode?}, output
-- {video: {url, ...}}) -- FalAiProvider's existing lipSyncRequestBody()/toLlmResponse() shaping
-- already happens to match this exactly (video_url/audio_url in, {video:{url}} out), so only the
-- model_master seed itself was wrong, not the provider code.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES ('fal-ai/sync-lipsync', 'fal.ai', 'lip_sync', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 120000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('fal-ai/sync-lipsync', 0, 0, 'USD', now());
