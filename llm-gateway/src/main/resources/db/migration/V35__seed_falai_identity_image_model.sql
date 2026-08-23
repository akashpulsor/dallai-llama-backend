-- Identity-preserving image generation (e.g. FLUX_PULID-style: face + scene rendered together in
-- one call, conditioned on 1-2 reference image URLs) -- routes through the existing fal.ai
-- meta-provider's new "image" modelType (see FalAiProvider.imageRequestBody/firstImageUrl).
-- model_id is a logical id, not yet verified against fal.ai's real app path, same caveat as
-- 'seedance-v1' in V14 -- confirm/adjust before this is called against a live fal.ai account.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'flux-pulid-v1',
    'fal.ai',
    'image',
    '{"referenceImages": true, "identityPreserving": true}'::jsonb,
    NULL,
    false,
    'active',
    10,
    1000000,
    60000
);

-- Placeholder only, same convention as every other non-token-billed fal.ai model in this file.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('flux-pulid-v1', 0, 0, 'USD', now());
