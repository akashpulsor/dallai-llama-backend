-- Cheap planning-image model for pre-production-service's LIGHTING / CAMERA_PLAN / MOTION_GRAPHIC
-- kinds -- schematic diagrams that don't need photoreal identity conditioning. FLUX schnell is
-- fal.ai's fastest/cheapest text-to-image at $0.003/image flat, ~12x cheaper than
-- gemini-2.5-flash-image; PRODUCTION + STORYBOARD keep Gemini for identity/product fidelity.
--
-- Billing: input_token_cost is the per-request flat rate (LlmGatewayService.computeCost multiplies
-- by inputTokens); FalAiProvider counts inputTokens=1 for image-typed calls -- same pattern the
-- foley branch already uses -- so a single call bills exactly $0.003. output_token_cost stays 0.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'fal-ai/flux/schnell',
    'fal.ai',
    'image',
    '{"modalities": ["image"]}'::jsonb,
    32768,
    false,
    'active',
    30,
    1000000,
    60000
);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('fal-ai/flux/schnell', 0.003, 0, 'USD', now());
