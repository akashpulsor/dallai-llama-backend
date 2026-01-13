-- Seed plans for AI Contact Center
INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default)
SELECT gen_random_uuid(), id, 'AI_CC_STARTER', 'AI CC Starter', 'STARTER', 4999.00, true
FROM products WHERE code = 'AI_CC';

INSERT INTO plans (id, product_id, code, name, tier, monthly_price)
SELECT gen_random_uuid(), id, 'AI_CC_PROFESSIONAL', 'AI CC Professional', 'PROFESSIONAL', 14999.00
FROM products WHERE code = 'AI_CC';

INSERT INTO plans (id, product_id, code, name, tier, monthly_price)
SELECT gen_random_uuid(), id, 'AI_CC_ENTERPRISE', 'AI CC Enterprise', 'ENTERPRISE', 49999.00
FROM products WHERE code = 'AI_CC';

-- Seed plans for Conversational IVR
INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default)
SELECT gen_random_uuid(), id, 'CONV_IVR_STARTER', 'Conversational IVR Starter', 'STARTER', 2999.00, true
FROM products WHERE code = 'CONV_IVR';

INSERT INTO plans (id, product_id, code, name, tier, monthly_price)
SELECT gen_random_uuid(), id, 'CONV_IVR_PROFESSIONAL', 'Conversational IVR Professional', 'PROFESSIONAL', 9999.00
FROM products WHERE code = 'CONV_IVR';

-- Seed plans for Basic PBX
INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default)
SELECT gen_random_uuid(), id, 'BASIC_PBX_STARTER', 'Basic PBX Starter', 'STARTER', 1999.00, true
FROM products WHERE code = 'BASIC_PBX';

-- Seed entitlements for AI CC Starter
INSERT INTO plan_entitlements (id, plan_id, max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, recording_storage_gb, recording_retention_days)
SELECT gen_random_uuid(), id, 5, 1, 5, 2, 3, 2, 10, 30
FROM plans WHERE code = 'AI_CC_STARTER';

-- Seed entitlements for AI CC Professional
INSERT INTO plan_entitlements (id, plan_id, max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, recording_storage_gb, recording_retention_days, ai_stt_enabled, ai_llm_enabled, ai_sentiment_enabled, ai_tokens_per_month, barge_enabled, whisper_enabled)
SELECT gen_random_uuid(), id, 25, 5, 20, 10, 10, 10, 50, 90, true, true, true, 100000, true, true
FROM plans WHERE code = 'AI_CC_PROFESSIONAL';

-- Seed entitlements for AI CC Enterprise
INSERT INTO plan_entitlements (id, plan_id, max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, recording_storage_gb, recording_retention_days, ai_stt_enabled, ai_llm_enabled, ai_sentiment_enabled, ai_bot_enabled, ai_tokens_per_month, barge_enabled, whisper_enabled, conference_enabled, callback_enabled)
SELECT gen_random_uuid(), id, 100, 20, 100, 50, 50, 50, 500, 365, true, true, true, true, 1000000, true, true, true, true
FROM plans WHERE code = 'AI_CC_ENTERPRISE';

-- Seed entitlements for Conversational IVR Starter
INSERT INTO plan_entitlements (id, plan_id, max_agents, max_pstn_channels, max_dids, max_ivr_flows, ai_stt_enabled, ai_llm_enabled, ai_bot_enabled, ai_tokens_per_month, recording_enabled)
SELECT gen_random_uuid(), id, 0, 5, 2, 5, true, true, true, 50000, true
FROM plans WHERE code = 'CONV_IVR_STARTER';

-- Seed entitlements for Conversational IVR Professional
INSERT INTO plan_entitlements (id, plan_id, max_agents, max_pstn_channels, max_dids, max_ivr_flows, ai_stt_enabled, ai_llm_enabled, ai_bot_enabled, ai_sentiment_enabled, ai_tokens_per_month, recording_enabled)
SELECT gen_random_uuid(), id, 5, 20, 10, 20, true, true, true, true, 200000, true
FROM plans WHERE code = 'CONV_IVR_PROFESSIONAL';

-- Seed entitlements for Basic PBX Starter
INSERT INTO plan_entitlements (id, plan_id, max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, recording_storage_gb, recording_retention_days)
SELECT gen_random_uuid(), id, 10, 2, 10, 5, 5, 20, 30
FROM plans WHERE code = 'BASIC_PBX_STARTER';