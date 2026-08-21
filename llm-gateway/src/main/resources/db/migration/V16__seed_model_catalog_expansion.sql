-- Broadens the catalog per model_master.type so post-production-service's per-request model
-- overrides actually have real candidates to choose between, not just one hardcoded default each.
-- All still route through FalAiProvider's per-type request/response shaping (V15's caveat still
-- applies: field names are best-effort until a specific model is actually tested live).

-- More lip-sync candidates, matching the four named in the design discussion.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES
    ('sync-so-v1', 'fal.ai', 'lip_sync', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 120000),
    ('runway-act-two-v1', 'fal.ai', 'lip_sync', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 120000),
    ('musetalk-v1', 'fal.ai', 'lip_sync', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 120000),
    -- Foley/music generation types, new -- no model registered yet under either until a specific
    -- fal.ai app is chosen and confirmed; placeholder rows removed once real ones are added, not
    -- left as permanently-broken routing targets.
    ('foley-v1', 'fal.ai', 'foley', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 60000),
    ('music-gen-v1', 'fal.ai', 'music', '{}'::jsonb, NULL, false, 'active', 10, 1000000, 90000);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES
    ('sync-so-v1', 0, 0, 'USD', now()),
    ('runway-act-two-v1', 0, 0, 'USD', now()),
    ('musetalk-v1', 0, 0, 'USD', now()),
    ('foley-v1', 0, 0, 'USD', now()),
    ('music-gen-v1', 0, 0, 'USD', now());
