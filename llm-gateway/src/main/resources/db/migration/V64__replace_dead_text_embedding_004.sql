-- text-embedding-004 was shut down by Google on 2026-01-14 -- every embedContent call against it
-- has been 404ing since (confirmed live: embedded_document in chat-service has zero rows, all-
-- time; critic-service's SimilarFeedbackService has the same dead default). Additive, not a
-- rename: model_master.model_id is a real FK target (rate_card, llm_job, model_capability,
-- tenant_model_override), and pre-existing llm_job rows already reference 'text-embedding-004' --
-- renaming the primary key value out from under them would break those, so the dead model is
-- marked deprecated (not deleted) and gemini-embedding-001 is added alongside it. Callers
-- (chat-service, critic-service) get their own config-default changes to actually pick it up.
-- Dimensions changed too (768 -> 3072), but nothing here stores a fixed-width vector (chat-
-- service's embedded_document.embedding is a plain double precision[], not a sized pgvector
-- column) and the table is empty, so there's no backfill/migration of old vectors to do.
UPDATE model_master SET status = 'deprecated' WHERE model_id = 'text-embedding-004';

INSERT INTO model_master (model_id, provider_id, type, capabilities, context_window, supports_streaming, status, default_rpm, default_tpm, timeout_ms)
VALUES (
    'gemini-embedding-001',
    'google',
    'embedding',
    '{"dimensions": 3072}'::jsonb,
    2048,
    false,
    'active',
    100,
    500000,
    15000
);

INSERT INTO rate_card (model_id, input_token_cost, output_token_cost, currency, effective_from)
VALUES ('gemini-embedding-001', 0.00000015, 0, 'USD', now());
