INSERT INTO provider (provider_id, name, adapter_class, status, account_rpm, account_tpm)
VALUES ('google', 'Google (Gemini)', 'com.dalai.llama.llmgateway.service.provider.GoogleGeminiProvider', 'active', NULL, NULL);

-- fal.ai is a meta-provider fronting many models behind one account-level cap; the adapter
-- exists (FalAiProvider) but functional routing is deferred, so mark it 'beta' so the router
-- can reject explicitly rather than 500 if a model is registered against it prematurely.
INSERT INTO provider (provider_id, name, adapter_class, status, account_rpm, account_tpm)
VALUES ('fal.ai', 'fal.ai', 'com.dalai.llama.llmgateway.service.provider.FalAiProvider', 'beta', NULL, NULL);

INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'gemini-2.5-flash',
    'google',
    'chat',
    '{"functionCalling": true, "modalities": ["text", "image"]}'::jsonb,
    1048576,
    false,
    'active',
    60,
    200000,
    30000
);

-- Placeholder pricing (Gemini 2.5 Flash, text, paid tier, approx. $0.30/1M input,
-- $2.50/1M output as of this writing) -- verify against current Gemini pricing before
-- relying on this for real billing.
INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('gemini-2.5-flash', 0.0000003, 0.0000025, 'USD', now());
