-- Closes two named gaps from the pre-production/critic-service design doc: storyboard image
-- generation (needs an image-typed model) and human-feedback embeddings (needs an
-- embedding-typed model). Both route through GoogleGeminiProvider, same as gemini-2.5-flash.
INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'gemini-2.5-flash-image',
    'google',
    'image',
    '{"modalities": ["image"]}'::jsonb,
    32768,
    false,
    'active',
    20,
    100000,
    60000
);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('gemini-2.5-flash-image', 0.0000003, 0.00003, 'USD', now());

INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'text-embedding-004',
    'google',
    'embedding',
    '{"dimensions": 768}'::jsonb,
    2048,
    false,
    'active',
    100,
    500000,
    15000
);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('text-embedding-004', 0.0000001, 0, 'USD', now());
