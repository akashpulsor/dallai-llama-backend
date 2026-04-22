-- ============================================================
-- V23__seed_aicc_budget_plan.sql
-- Adds AICC_BUDGET plan for AI Contact Center (budget tier)
-- ============================================================

-- 1. Seed AICC_BUDGET plan
INSERT INTO plans (
    id, product_id, code, name, tier, monthly_price,
    is_default, active,
    per_agent_fee, included_agents, included_minutes, included_channels,
    ai_stack_type, ai_rate_per_min, minimum_wallet_balance
)
SELECT
    gen_random_uuid(), id, 'AICC_BUDGET', 'AI CC Budget', 'BUDGET', 1999.00,
    false, true,
    299, 3, 500, 2,
    'BUDGET', 3.50, 500
FROM products WHERE code = 'AI_CC'
AND NOT EXISTS (SELECT 1 FROM plans WHERE code = 'AICC_BUDGET');

-- 2. Seed entitlements for AICC_BUDGET
INSERT INTO plan_entitlements (
    id, plan_id,
    max_agents, max_supervisors, max_pstn_channels, max_dids,
    max_queues, max_ivr_flows, max_ring_groups, max_extensions,
    recording_enabled, recording_storage_gb, recording_retention_days,
    ai_stt_enabled, ai_llm_enabled, ai_tokens_per_month,
    blind_transfer_enabled,
    basic_ivr_enabled,
    api_access_enabled,
    basic_reporting_enabled,
    included_minutes_inbound, included_minutes_outbound,
    rate_per_minute_inbound, rate_per_minute_outbound,
    ai_rate_per_minute,
    sla_tier
)
SELECT
    gen_random_uuid(), p.id,
    3, 1, 2, 1,
    2, 1, 2, 5,
    true, 5, 15,
    true, true, 20000,
    true,
    true,
    true,
    true,
    500, 100,
    2.00, 2.50,
    0.60,
    'STANDARD'
FROM plans p WHERE p.code = 'AICC_BUDGET'
AND NOT EXISTS (SELECT 1 FROM plan_entitlements WHERE plan_id = p.id);
