-- Prerequisite for video-generation-service to dispatch anything at all: ModelRouterService.route()
-- looks up model_master by model_id and throws 404 if missing, and requires an active rate_card
-- row too -- without this row, EVERY video generation request fails at routing, before fal.ai is
-- ever called (confirmed missing from the live DB; this closes that gap).
--
-- default_tpm has no real meaning for video (no token concept) and is not currently read by
-- RateLimiterService (it only enforces RPM) -- set to a high placeholder so it never binds.
--
-- timeout_ms=180000 (3 min) must stay under video-generation-service's own client-side timeout to
-- this call (video-gen.llm-gateway.timeout-ms, currently 200000) with margin, or video-gen gives
-- up on its own HTTP call before llm-gateway ever returns its own timeout error -- see
-- LlmGatewayService's "+5000ms safety margin on top" comment for why 180000+5000=185000 < 200000
-- matters here.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'seedance-v1',
    'fal.ai',
    'video',
    '{"referenceImages": true, "textToVideo": true, "imageToVideo": true}'::jsonb,
    NULL,
    false,
    'active',
    10,
    1000000,
    180000
);

-- Placeholder only -- video billing is NOT actually token-based (FalAiProvider always reports
-- input/output tokens as 0, so computeCost() currently always evaluates to 0 for this model
-- regardless of these numbers). Real duration/per-video pricing needs its own rate_card shape
-- (e.g. cost-per-second) before tenants can be correctly billed for a video generation -- tracked
-- as a follow-up, not fabricated here.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('seedance-v1', 0, 0, 'USD', now());
