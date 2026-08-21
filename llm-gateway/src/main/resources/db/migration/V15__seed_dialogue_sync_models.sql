-- Prerequisite for post-production-service's DialogueSyncCoordinator to route at all -- without
-- these rows, ModelRouterService.route() 404s before fal.ai is ever called, same gap seedance-v1
-- had (V14) before being seeded. FalAiProvider now has per-type request/response shaping (see its
-- own class comment) but the exact field names are still best-effort, unverified against any one
-- specific fal.ai model's real schema -- these seeds unblock ROUTING, not correctness of the
-- actual provider call, which needs a real model chosen and tested before it will work end to end.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES
    ('voice-clone-v1', 'fal.ai', 'voice_clone', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 60000),
    ('wav2lip-v1', 'fal.ai', 'lip_sync', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 120000),
    ('tts-v1', 'fal.ai', 'tts', '{}'::jsonb, NULL, false, 'active', 20, 1000000, 30000);

-- Placeholder cost, same caveat as V14's seedance-v1 rate_card: these model types aren't
-- token-based, real per-second/per-character billing needs its own design before relying on
-- these numbers.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES
    ('voice-clone-v1', 0, 0, 'USD', now()),
    ('wav2lip-v1', 0, 0, 'USD', now()),
    ('tts-v1', 0, 0, 'USD', now());
