-- beatoven/sound-effect-generation: the real fal.ai foley/sound-effect app -- foley-v1 (still
-- seeded, V15/V16) was never a real fal.ai endpoint; every dispatch against it would fail
-- outright, not just bill $0. Real, flat $0.01/request, verified against fal.ai's published price.
-- FalAiProvider.toLlmResponse now reports a flat 1-unit input-token count for type=foley, so
-- rate_card.input_token_cost expresses the real per-request price with no new billing branch.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'beatoven/sound-effect-generation',
    'fal.ai',
    'foley',
    '{"textToAudio": true, "maxDurationSeconds": 35}'::jsonb,
    NULL,
    false,
    'active',
    10,
    1000000,
    60000
);
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('beatoven/sound-effect-generation', 0.01, 0, 'USD', now());

-- fal-ai/minimax/voice-clone: real, verified two-part pricing -- $1.50 flat per clone request,
-- plus $0.30/1000 characters ($0.0003/char) only when the fused call also synthesizes a preview
-- (params.text present). FalAiProvider.toLlmResponse now reports a flat 1-unit input-token count
-- (the $1.50 base) and real character-count output tokens (the preview-synthesis charge) for
-- type=voice_clone, so this single rate_card row expresses both real components with no new
-- billing branch -- input_token_cost carries the flat fee, output_token_cost the per-character
-- preview rate.
UPDATE rate_card SET input_token_cost = 1.5, output_token_cost = 0.0003 WHERE model_id = 'fal-ai/minimax/voice-clone';
