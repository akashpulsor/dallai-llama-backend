-- New model_master type: 'upscale'. First catalog entry is Topaz Video Upscale on fal.ai
-- (fal-ai/topaz/upscale/video), verified against fal.ai's real published API reference --
-- request wants video_url (mapped from this codebase's source_video_url convention by
-- FalAiProvider.upscaleRequestBody), response is fal's common {video: {url}} envelope (already
-- handled by FalAiProvider.toLlmResponse's default case, no code change needed there).
--
-- Wan 3.0 has no upscale mode of its own on fal.ai (checked; it's a text/image/reference-to-video
-- generator only) -- not seeded here. Additional upscaler apps (e.g. fal-ai/video-upscaler) are a
-- follow-up: add another model_master row the same shape, no new code, same as every model in
-- this catalog.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'fal-ai/topaz/upscale/video',
    'fal.ai',
    'upscale',
    '{"videoUpscale": true, "maxUpscaleFactor": 8}'::jsonb,
    NULL,
    false,
    'active',
    10,
    1000000,
    180000
);

-- Placeholder only, same pre-existing "video isn't token-priced yet" gap as every other fal.ai
-- video-family model (see V14/V71's identical comment) -- real per-call pricing needs its own
-- rate_card shape, tracked as a follow-up, not fabricated here.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('fal-ai/topaz/upscale/video', 0, 0, 'USD', now());
