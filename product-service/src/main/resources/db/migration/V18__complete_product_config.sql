-- ============================================================
-- V13__complete_product_config.sql
-- Adds missing columns and seed data for hybrid architecture
-- ============================================================

-- ==================== 1. ADD MISSING COLUMNS TO plan_entitlements ====================

-- Capacity (some might exist)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS max_ring_groups INT DEFAULT 3;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS max_extensions INT DEFAULT 10;

-- AI Features (new ones)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_routing_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_noise_cancellation_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_voice_morph_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_agent_assist_enabled BOOLEAN DEFAULT false;

-- Call Features (new ones)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS listen_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS blind_transfer_enabled BOOLEAN DEFAULT true;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS attended_transfer_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS warm_transfer_enabled BOOLEAN DEFAULT false;

-- Recording (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS screen_recording_enabled BOOLEAN DEFAULT false;

-- IVR Features (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS basic_ivr_enabled BOOLEAN DEFAULT true;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS conversational_ivr_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ivr_multi_language_enabled BOOLEAN DEFAULT false;

-- Dialer Features (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS progressive_dialer_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS predictive_dialer_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS preview_dialer_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS amd_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS dnc_management_enabled BOOLEAN DEFAULT false;

-- Voicemail (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS voicemail_transcription_enabled BOOLEAN DEFAULT false;

-- Integrations (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS crm_integration_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS screen_pop_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS api_access_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS webhook_enabled BOOLEAN DEFAULT false;

-- Reporting (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS basic_reporting_enabled BOOLEAN DEFAULT true;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS advanced_reporting_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS custom_reports_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS wallboard_enabled BOOLEAN DEFAULT false;

-- Usage Limits (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS included_minutes_inbound INT DEFAULT 0;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS included_minutes_outbound INT DEFAULT 0;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS rate_per_minute_inbound DECIMAL(10,4) DEFAULT 0;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS rate_per_minute_outbound DECIMAL(10,4) DEFAULT 0;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS ai_rate_per_minute DECIMAL(10,4) DEFAULT 0;

-- Deployment (new)
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS dedicated_infrastructure BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS custom_domain_enabled BOOLEAN DEFAULT false;
ALTER TABLE plan_entitlements ADD COLUMN IF NOT EXISTS sla_tier VARCHAR(20) DEFAULT 'STANDARD';


-- ==================== 2. SEED MISSING PLANS ====================

-- Plans for OUTBOUND_DIALER (if not exists)
INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default, active, per_agent_fee, included_agents, included_minutes, ai_stack_type, ai_rate_per_min)
SELECT gen_random_uuid(), id, 'DIALER_STARTER', 'Dialer Starter', 'STARTER', 3999.00, true, true, 499, 5, 2000, NULL, NULL
FROM products WHERE code = 'OUTBOUND_DIALER'
AND NOT EXISTS (SELECT 1 FROM plans WHERE code = 'DIALER_STARTER');

INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default, active, per_agent_fee, included_agents, included_minutes, ai_stack_type, ai_rate_per_min)
SELECT gen_random_uuid(), id, 'DIALER_PROFESSIONAL', 'Dialer Professional', 'PROFESSIONAL', 12999.00, false, true, 399, 10, 10000, 'STANDARD', 5.50
FROM products WHERE code = 'OUTBOUND_DIALER'
AND NOT EXISTS (SELECT 1 FROM plans WHERE code = 'DIALER_PROFESSIONAL');

-- Plans for VIRTUAL_RECEPTIONIST (if not exists)
INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default, active, per_agent_fee, included_agents, included_minutes, ai_stack_type, ai_rate_per_min)
SELECT gen_random_uuid(), id, 'RECEPTIONIST_STARTER', 'Virtual Receptionist Starter', 'STARTER', 1999.00, true, true, 0, 0, 500, 'BUDGET', 3.50
FROM products WHERE code = 'VIRTUAL_RECEPTIONIST'
AND NOT EXISTS (SELECT 1 FROM plans WHERE code = 'RECEPTIONIST_STARTER');

INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default, active, per_agent_fee, included_agents, included_minutes, ai_stack_type, ai_rate_per_min)
SELECT gen_random_uuid(), id, 'RECEPTIONIST_PROFESSIONAL', 'Virtual Receptionist Professional', 'PROFESSIONAL', 4999.00, false, true, 0, 0, 2000, 'STANDARD', 5.50
FROM products WHERE code = 'VIRTUAL_RECEPTIONIST'
AND NOT EXISTS (SELECT 1 FROM plans WHERE code = 'RECEPTIONIST_PROFESSIONAL');

-- Plans for BASIC_PBX Professional (if not exists)
INSERT INTO plans (id, product_id, code, name, tier, monthly_price, is_default, active, per_agent_fee, included_agents, included_minutes)
SELECT gen_random_uuid(), id, 'BASIC_PBX_PROFESSIONAL', 'Basic PBX Professional', 'PROFESSIONAL', 4999.00, false, true, 399, 10, 2000
FROM products WHERE code = 'BASIC_PBX'
AND NOT EXISTS (SELECT 1 FROM plans WHERE code = 'BASIC_PBX_PROFESSIONAL');


-- ==================== 3. UPDATE EXISTING ENTITLEMENTS ====================

-- AI_CC_STARTER - Update with all new columns
UPDATE plan_entitlements SET
    max_ring_groups = 3,
    max_extensions = 10,
    listen_enabled = false,
    blind_transfer_enabled = true,
    attended_transfer_enabled = false,
    warm_transfer_enabled = false,
    basic_ivr_enabled = true,
    conversational_ivr_enabled = false,
    api_access_enabled = true,
    basic_reporting_enabled = true,
    included_minutes_inbound = 500,
    included_minutes_outbound = 200,
    rate_per_minute_inbound = 1.50,
    rate_per_minute_outbound = 2.00,
    sla_tier = 'STANDARD'
WHERE plan_id IN (SELECT id FROM plans WHERE code = 'AI_CC_STARTER');

-- AI_CC_PROFESSIONAL
UPDATE plan_entitlements SET
    max_ring_groups = 10,
    max_extensions = 50,
    ai_routing_enabled = true,
    ai_agent_assist_enabled = true,
    listen_enabled = true,
    blind_transfer_enabled = true,
    attended_transfer_enabled = true,
    warm_transfer_enabled = true,
    basic_ivr_enabled = true,
    conversational_ivr_enabled = true,
    ivr_multi_language_enabled = true,
    voicemail_transcription_enabled = true,
    crm_integration_enabled = true,
    screen_pop_enabled = true,
    api_access_enabled = true,
    webhook_enabled = true,
    basic_reporting_enabled = true,
    advanced_reporting_enabled = true,
    wallboard_enabled = true,
    included_minutes_inbound = 2000,
    included_minutes_outbound = 1000,
    rate_per_minute_inbound = 1.25,
    rate_per_minute_outbound = 1.75,
    ai_rate_per_minute = 0.50,
    sla_tier = 'PREMIUM'
WHERE plan_id IN (SELECT id FROM plans WHERE code = 'AI_CC_PROFESSIONAL');

-- AI_CC_ENTERPRISE
UPDATE plan_entitlements SET
    max_ring_groups = 50,
    max_extensions = 500,
    ai_routing_enabled = true,
    ai_noise_cancellation_enabled = true,
    ai_voice_morph_enabled = true,
    ai_agent_assist_enabled = true,
    listen_enabled = true,
    blind_transfer_enabled = true,
    attended_transfer_enabled = true,
    warm_transfer_enabled = true,
    screen_recording_enabled = true,
    basic_ivr_enabled = true,
    conversational_ivr_enabled = true,
    ivr_multi_language_enabled = true,
    progressive_dialer_enabled = true,
    predictive_dialer_enabled = true,
    preview_dialer_enabled = true,
    amd_enabled = true,
    dnc_management_enabled = true,
    voicemail_transcription_enabled = true,
    crm_integration_enabled = true,
    screen_pop_enabled = true,
    api_access_enabled = true,
    webhook_enabled = true,
    basic_reporting_enabled = true,
    advanced_reporting_enabled = true,
    custom_reports_enabled = true,
    wallboard_enabled = true,
    included_minutes_inbound = 10000,
    included_minutes_outbound = 5000,
    rate_per_minute_inbound = 1.00,
    rate_per_minute_outbound = 1.50,
    ai_rate_per_minute = 0.30,
    dedicated_infrastructure = true,
    custom_domain_enabled = true,
    sla_tier = 'ENTERPRISE'
WHERE plan_id IN (SELECT id FROM plans WHERE code = 'AI_CC_ENTERPRISE');

-- CONV_IVR entitlements
UPDATE plan_entitlements SET
    max_ring_groups = 0,
    max_extensions = 0,
    basic_ivr_enabled = true,
    conversational_ivr_enabled = true,
    voicemail_transcription_enabled = true,
    api_access_enabled = true,
    webhook_enabled = true,
    basic_reporting_enabled = true,
    included_minutes_inbound = 1000,
    rate_per_minute_inbound = 1.50,
    ai_rate_per_minute = 0.50,
    sla_tier = 'STANDARD'
WHERE plan_id IN (SELECT id FROM plans WHERE code = 'CONV_IVR_STARTER');

UPDATE plan_entitlements SET
    max_ring_groups = 0,
    max_extensions = 10,
    ai_routing_enabled = true,
    basic_ivr_enabled = true,
    conversational_ivr_enabled = true,
    ivr_multi_language_enabled = true,
    voicemail_transcription_enabled = true,
    crm_integration_enabled = true,
    screen_pop_enabled = true,
    api_access_enabled = true,
    webhook_enabled = true,
    basic_reporting_enabled = true,
    advanced_reporting_enabled = true,
    included_minutes_inbound = 5000,
    rate_per_minute_inbound = 1.25,
    ai_rate_per_minute = 0.40,
    sla_tier = 'PREMIUM'
WHERE plan_id IN (SELECT id FROM plans WHERE code = 'CONV_IVR_PROFESSIONAL');

-- BASIC_PBX_STARTER
UPDATE plan_entitlements SET
    max_ring_groups = 5,
    max_extensions = 20,
    blind_transfer_enabled = true,
    attended_transfer_enabled = true,
    warm_transfer_enabled = true,
    basic_ivr_enabled = true,
    api_access_enabled = true,
    basic_reporting_enabled = true,
    included_minutes_inbound = 500,
    included_minutes_outbound = 500,
    rate_per_minute_inbound = 1.50,
    rate_per_minute_outbound = 2.00,
    sla_tier = 'STANDARD'
WHERE plan_id IN (SELECT id FROM plans WHERE code = 'BASIC_PBX_STARTER');


-- ==================== 4. CREATE MISSING ENTITLEMENTS ====================

-- DIALER_STARTER
INSERT INTO plan_entitlements (
    id, plan_id,
    max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, max_ring_groups, max_extensions,
    barge_enabled, whisper_enabled, listen_enabled, blind_transfer_enabled, attended_transfer_enabled,
    recording_enabled, recording_storage_gb, recording_retention_days,
    basic_ivr_enabled,
    progressive_dialer_enabled, preview_dialer_enabled, amd_enabled, dnc_management_enabled,
    crm_integration_enabled, screen_pop_enabled, api_access_enabled, webhook_enabled,
    basic_reporting_enabled, wallboard_enabled,
    included_minutes_outbound, rate_per_minute_outbound,
    sla_tier
)
SELECT gen_random_uuid(), p.id,
    10, 2, 15, 3, 3, 2, 0, 10,
    true, true, true, true, true,
    true, 20, 30,
    true,
    true, true, true, true,
    true, true, true, true,
    true, true,
    2000, 1.50,
    'STANDARD'
FROM plans p WHERE p.code = 'DIALER_STARTER'
AND NOT EXISTS (SELECT 1 FROM plan_entitlements WHERE plan_id = p.id);

-- DIALER_PROFESSIONAL
INSERT INTO plan_entitlements (
    id, plan_id,
    max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, max_ring_groups, max_extensions,
    ai_stt_enabled, ai_sentiment_enabled, ai_voice_morph_enabled, ai_agent_assist_enabled, ai_tokens_per_month,
    barge_enabled, whisper_enabled, listen_enabled, conference_enabled, blind_transfer_enabled, attended_transfer_enabled, warm_transfer_enabled,
    recording_enabled, recording_storage_gb, recording_retention_days, screen_recording_enabled,
    basic_ivr_enabled,
    progressive_dialer_enabled, predictive_dialer_enabled, preview_dialer_enabled, amd_enabled, dnc_management_enabled,
    crm_integration_enabled, screen_pop_enabled, api_access_enabled, webhook_enabled,
    basic_reporting_enabled, advanced_reporting_enabled, custom_reports_enabled, wallboard_enabled,
    included_minutes_outbound, rate_per_minute_outbound, ai_rate_per_minute,
    sla_tier
)
SELECT gen_random_uuid(), p.id,
    50, 10, 50, 10, 10, 5, 5, 50,
    true, true, true, true, 50000,
    true, true, true, true, true, true, true,
    true, 100, 90, true,
    true,
    true, true, true, true, true,
    true, true, true, true,
    true, true, true, true,
    10000, 1.25, 0.40,
    'PREMIUM'
FROM plans p WHERE p.code = 'DIALER_PROFESSIONAL'
AND NOT EXISTS (SELECT 1 FROM plan_entitlements WHERE plan_id = p.id);

-- RECEPTIONIST_STARTER
INSERT INTO plan_entitlements (
    id, plan_id,
    max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, max_ring_groups, max_extensions,
    ai_stt_enabled, ai_llm_enabled, ai_bot_enabled, ai_tokens_per_month,
    blind_transfer_enabled,
    recording_enabled, recording_storage_gb, recording_retention_days,
    basic_ivr_enabled, conversational_ivr_enabled,
    voicemail_enabled, voicemail_transcription_enabled,
    api_access_enabled, webhook_enabled,
    basic_reporting_enabled,
    included_minutes_inbound, rate_per_minute_inbound, ai_rate_per_minute,
    sla_tier
)
SELECT gen_random_uuid(), p.id,
    0, 0, 3, 1, 0, 3, 0, 5,
    true, true, true, 30000,
    true,
    true, 5, 30,
    true, true,
    true, true,
    true, true,
    true,
    500, 1.50, 0.60,
    'STANDARD'
FROM plans p WHERE p.code = 'RECEPTIONIST_STARTER'
AND NOT EXISTS (SELECT 1 FROM plan_entitlements WHERE plan_id = p.id);

-- RECEPTIONIST_PROFESSIONAL
INSERT INTO plan_entitlements (
    id, plan_id,
    max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, max_ring_groups, max_extensions,
    ai_stt_enabled, ai_llm_enabled, ai_bot_enabled, ai_sentiment_enabled, ai_routing_enabled, ai_tokens_per_month,
    blind_transfer_enabled, attended_transfer_enabled, callback_enabled,
    recording_enabled, recording_storage_gb, recording_retention_days,
    basic_ivr_enabled, conversational_ivr_enabled, ivr_multi_language_enabled,
    voicemail_enabled, voicemail_transcription_enabled,
    crm_integration_enabled, screen_pop_enabled, api_access_enabled, webhook_enabled,
    basic_reporting_enabled, advanced_reporting_enabled,
    included_minutes_inbound, rate_per_minute_inbound, ai_rate_per_minute,
    sla_tier
)
SELECT gen_random_uuid(), p.id,
    5, 1, 10, 5, 2, 10, 3, 20,
    true, true, true, true, true, 100000,
    true, true, true,
    true, 20, 90,
    true, true, true,
    true, true,
    true, true, true, true,
    true, true,
    2000, 1.25, 0.40,
    'PREMIUM'
FROM plans p WHERE p.code = 'RECEPTIONIST_PROFESSIONAL'
AND NOT EXISTS (SELECT 1 FROM plan_entitlements WHERE plan_id = p.id);

-- BASIC_PBX_PROFESSIONAL
INSERT INTO plan_entitlements (
    id, plan_id,
    max_agents, max_supervisors, max_pstn_channels, max_dids, max_queues, max_ivr_flows, max_ring_groups, max_extensions,
    barge_enabled, whisper_enabled, listen_enabled, conference_enabled, callback_enabled,
    blind_transfer_enabled, attended_transfer_enabled, warm_transfer_enabled,
    recording_enabled, recording_storage_gb, recording_retention_days,
    basic_ivr_enabled,
    voicemail_enabled,
    api_access_enabled, webhook_enabled,
    basic_reporting_enabled, advanced_reporting_enabled,
    included_minutes_inbound, included_minutes_outbound, rate_per_minute_inbound, rate_per_minute_outbound,
    sla_tier
)
SELECT gen_random_uuid(), p.id,
    25, 5, 25, 10, 10, 10, 10, 50,
    true, true, true, true, true,
    true, true, true,
    true, 50, 90,
    true,
    true,
    true, true,
    true, true,
    1000, 1000, 1.25, 1.75,
    'PREMIUM'
FROM plans p WHERE p.code = 'BASIC_PBX_PROFESSIONAL'
AND NOT EXISTS (SELECT 1 FROM plan_entitlements WHERE plan_id = p.id);


-- ==================== 5. SEED MISSING PRODUCT_APPS ====================

-- OUTBOUND_DIALER apps
INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'DIALER', 'dialer', 'Dialer Dashboard', NULL, 'dalaillama/dialer-ui:latest', 'SUPERVISOR,TENANT_ADMIN', '📤', 0
FROM products p WHERE p.code = 'OUTBOUND_DIALER'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'DIALER');

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'AGENT_DIALER', 'agent', 'Agent Dialer', 'agent', 'dalaillama/agent-dialer-ui:latest', 'AGENT,SUPERVISOR', '📞', 1
FROM products p WHERE p.code = 'OUTBOUND_DIALER'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'AGENT_DIALER');

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 'TENANT_ADMIN', '⚙️', 2
FROM products p WHERE p.code = 'OUTBOUND_DIALER'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'ADMIN_PANEL');

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'REPORTING', 'reports', 'Campaign Reports', 'reporting', 'dalaillama/reporting-ui:latest', 'SUPERVISOR,TENANT_ADMIN', '📈', 3
FROM products p WHERE p.code = 'OUTBOUND_DIALER'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'REPORTING');

-- VIRTUAL_RECEPTIONIST apps
INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'RECEPTIONIST', 'receptionist', 'Receptionist Dashboard', NULL, 'dalaillama/receptionist-ui:latest', 'TENANT_ADMIN', '🤖', 0
FROM products p WHERE p.code = 'VIRTUAL_RECEPTIONIST'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'RECEPTIONIST');

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'ADMIN_PANEL', 'admin', 'Admin Panel', 'admin', 'dalaillama/admin-ui:latest', 'TENANT_ADMIN', '⚙️', 1
FROM products p WHERE p.code = 'VIRTUAL_RECEPTIONIST'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'ADMIN_PANEL');

INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'REPORTING', 'reports', 'Call Analytics', 'reporting', 'dalaillama/reporting-ui:latest', 'TENANT_ADMIN', '📈', 2
FROM products p WHERE p.code = 'VIRTUAL_RECEPTIONIST'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'REPORTING');

-- Add REPORTING app to CONV_IVR (was missing)
INSERT INTO product_apps (id, product_id, app_type, subdomain, display_name, keycloak_client_suffix, frontend_image, required_roles, icon, display_order)
SELECT gen_random_uuid(), p.id, 'REPORTING', 'reports', 'Analytics', 'reporting', 'dalaillama/reporting-ui:latest', 'TENANT_ADMIN', '📈', 2
FROM products p WHERE p.code = 'CONV_IVR'
AND NOT EXISTS (SELECT 1 FROM product_apps WHERE product_id = p.id AND app_type = 'REPORTING');