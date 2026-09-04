-- Wan 3.0 Prime (Alibaba), image-to-video: fal.ai endpoint id verified against fal.ai's real
-- published API reference (alibaba/wan-3.0-prime/image-to-video) -- request schema wants
-- start_image_url/audio (not Seedance's image_url/generate_audio), handled by
-- FalAiProvider.wanVideoRequestBody, dispatched by model_id rather than a dedicated type so this
-- model still bills/lists exactly like every other type=video row (per-second billing branch in
-- LlmGatewayService.computeCost, GET /v1/models?type=video).
--
-- default_tpm/timeout_ms follow the same reasoning as V14's Seedance seed: no real token concept
-- for video, and timeout_ms must stay comfortably under video-generation-service's own
-- video-gen.llm-gateway.timeout-ms client-side timeout.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'alibaba/wan-3.0-prime',
    'fal.ai',
    'video',
    '{"textToVideo": true, "imageToVideo": true}'::jsonb,
    NULL,
    false,
    'active',
    10,
    1000000,
    180000
);

-- Placeholder only, same as every other fal.ai video model today -- FalAiProvider always reports
-- 0 input/output tokens, so computeCost() evaluates to 0 regardless of these numbers until video
-- gets its own real duration-based rate_card row (see V14's identical comment; tracked as the
-- same pre-existing follow-up, not solved here).
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('alibaba/wan-3.0-prime', 0, 0, 'USD', now());
