-- gemini-2.5-flash-image reaches EOL 2026-10-02. Seed the two replacement models so
-- pre-production-service can point at them via config: gemini-3.1-flash-image is the direct
-- successor for PRODUCTION frames (video-generation anchor -- photoreal quality matters);
-- gemini-3.1-flash-lite-image is the budget variant for STORYBOARD (planning sketch, budget
-- fidelity acceptable). Same GoogleGeminiProvider dispatch path -- only the model_id changes.
--
-- Rate cards use Google's real per-token pricing so a call bills on the actual token counts
-- Gemini reports back (not a flat per-image bucket). Numbers per Google's Sept-2026 pricing page:
--   gemini-3.1-flash-image      -- input $0.50/1M, output (images) $60.00/1M -> ~$0.067 per 1K image
--   gemini-3.1-flash-lite-image -- input $0.25/1M, output (images) $30.00/1M -> ~$0.0336 per 1K image
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES
    ('gemini-3.1-flash-image',      'google', 'image', '{"modalities": ["image"]}'::jsonb, 32768, false, 'active', 20, 100000, 60000),
    ('gemini-3.1-flash-lite-image', 'google', 'image', '{"modalities": ["image"]}'::jsonb, 32768, false, 'active', 30, 100000, 60000)
ON CONFLICT (model_id) DO NOTHING;

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES
    ('gemini-3.1-flash-image',      0.0000005,  0.00006,  'USD', now()),
    ('gemini-3.1-flash-lite-image', 0.00000025, 0.00003, 'USD', now())
ON CONFLICT DO NOTHING;
