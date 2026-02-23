-- Migration: V12__tenant_app_complete_telecom_fields.sql
-- Complete TenantApp table with all subscription and telecom configuration

-- Drop existing table if migrating from old structure
-- ALTER TABLE tenant_apps ... (add columns incrementally if table exists)

-- Or create fresh table:

DROP TABLE IF EXISTS tenant_apps CASCADE;

CREATE TABLE IF NOT EXISTS tenant_apps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- ==================== SUBSCRIPTION ====================
    subscription_id UUID UNIQUE,
    app_type VARCHAR(50) NOT NULL,
    deployment_model VARCHAR(20), -- SHARED, DEDICATED
    subdomain VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,

    -- ==================== PRODUCT & PLAN ====================
    product_code VARCHAR(50),
    plan_id UUID,
    plan_code VARCHAR(50),
    plan_tier VARCHAR(30), -- STARTER, STANDARD, PROFESSIONAL, ENTERPRISE
    plan_assigned_at TIMESTAMP WITH TIME ZONE,

    -- ==================== ENTITLEMENTS ====================
    agent_seats INT,
    max_agents INT,
    max_supervisors INT,
    max_dids INT,
    max_channels INT,
    max_queues INT,
    max_ivr_flows INT,
    included_minutes INT,
    ai_rate_per_min DECIMAL(10,4),
    ai_tokens_per_month INT,
    recording_storage_gb INT,
    recording_retention_days INT,

    -- ==================== DID ====================
    did_id UUID,
    did_number VARCHAR(20),
    did_display_number VARCHAR(30),
    did_country VARCHAR(5),
    did_region VARCHAR(100),
    did_city VARCHAR(100),

    -- ==================== SIP ENDPOINT (Inbound DID Registration) ====================
    sip_endpoint_id UUID,
    sip_endpoint_username VARCHAR(100),
    sip_endpoint_password_hash VARCHAR(255),
    sip_endpoint_domain VARCHAR(100),
    sip_endpoint_realm VARCHAR(100),

    -- ==================== CHANNELS ====================
    channel_bundle_id UUID,
    channel_direction VARCHAR(20), -- INBOUND, OUTBOUND, BOTH
    channel_total INT,
    channel_inbound INT,
    channel_outbound INT,

    -- ==================== TENANT SIP TRUNK (Customer's credentials to YOUR platform) ====================
    tenant_trunk_id UUID,
    tenant_trunk_username VARCHAR(100),
    tenant_trunk_password_hash VARCHAR(255),
    tenant_trunk_domain VARCHAR(100),
    tenant_trunk_port INT,
    tenant_trunk_realm VARCHAR(100),
    tenant_trunk_transport VARCHAR(10), -- UDP, TCP, TLS
    tenant_trunk_max_calls INT,

    -- ==================== PLATFORM TRUNK (Epsilon - outbound) ====================
    platform_trunk_id UUID,
    platform_trunk_provider VARCHAR(50),
    platform_trunk_server VARCHAR(100),
    platform_trunk_port INT,
    platform_trunk_transport VARCHAR(10),
    platform_trunk_codecs JSONB,

    -- ==================== INFRASTRUCTURE ====================
    namespace VARCHAR(100),
    kafka_bootstrap VARCHAR(200),
    redis_url VARCHAR(200),
    postgres_url VARCHAR(500),
    mysql_url VARCHAR(500),

    -- ==================== SIP & TELEPHONY URLs ====================
    sip_external_ip VARCHAR(100),
    sip_udp_url VARCHAR(200),
    sip_tls_url VARCHAR(200),
    turn_url VARCHAR(200),
    websocket_url VARCHAR(200),
    rtpengine_sock VARCHAR(200),

    -- ==================== FREESWITCH ESL ====================
    freeswitch_esl_host VARCHAR(100),
    freeswitch_esl_port INT,
    freeswitch_esl_password VARCHAR(100),

    -- ==================== EXTERNAL INTEGRATIONS ====================
    didww_trunk_id VARCHAR(100),
    didww_sip_config_id VARCHAR(100),

    -- ==================== KEYCLOAK ====================
    keycloak_client_id VARCHAR(100),

    -- ==================== FRONTEND ====================
    frontend_service VARCHAR(100),
    frontend_image VARCHAR(200),
    frontend_port INT DEFAULT 80,
    dashboard_url VARCHAR(200),

    -- ==================== AI FEATURES ====================
    ai_transcription_enabled BOOLEAN DEFAULT false,
    ai_routing_enabled BOOLEAN DEFAULT false,
    ai_noise_cancellation_enabled BOOLEAN DEFAULT false,
    ai_sentiment_enabled BOOLEAN DEFAULT false,
    ai_bot_enabled BOOLEAN DEFAULT false,

    -- ==================== CALL FEATURES ====================
    barge_enabled BOOLEAN DEFAULT false,
    whisper_enabled BOOLEAN DEFAULT false,
    conference_enabled BOOLEAN DEFAULT false,
    callback_enabled BOOLEAN DEFAULT false,
    recording_enabled BOOLEAN DEFAULT false,

    -- ==================== CONFIGS (JSON/TEXT) ====================
    -- FreePBX dialplan configuration
    cc_builder_config TEXT,
    -- Kamailio configuration snippet
    kamailio_config TEXT,
    -- App panels JSON: [{appType, displayName, subdomain, url, icon, keycloakClientId, frontendImage, requiredRoles}]
    app_panels JSONB,

    -- ==================== UI & META ====================
    required_roles VARCHAR(200),
    description VARCHAR(500),
    icon VARCHAR(50),
    display_order INT DEFAULT 0,
    enabled BOOLEAN DEFAULT true,

    -- ==================== DEPLOYMENT STATUS ====================
    deployment_status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, IN_PROGRESS, COMPLETED, FAILED
    kamailio_synced BOOLEAN DEFAULT false,
    kamailio_synced_at TIMESTAMP WITH TIME ZONE,
    freepbx_synced BOOLEAN DEFAULT false,
    freepbx_synced_at TIMESTAMP WITH TIME ZONE,
    deployed_at TIMESTAMP WITH TIME ZONE,

    -- ==================== TIMESTAMPS ====================
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    version BIGINT DEFAULT 0,

    -- ==================== CONSTRAINTS ====================
    CONSTRAINT uk_tenant_app_subscription UNIQUE (subscription_id),
    CONSTRAINT uk_tenant_app_did UNIQUE (did_number)
);

-- Indexes
CREATE INDEX idx_tenant_app_tenant ON tenant_apps(tenant_id);
CREATE INDEX idx_tenant_app_subscription ON tenant_apps(subscription_id);
CREATE INDEX idx_tenant_app_did ON tenant_apps(did_number);
CREATE INDEX idx_tenant_app_product ON tenant_apps(product_code);
CREATE INDEX idx_tenant_app_status ON tenant_apps(deployment_status);
CREATE INDEX idx_tenant_app_namespace ON tenant_apps(namespace);

-- ==================== COMMENTS ====================
COMMENT ON TABLE tenant_apps IS 'Single entry per subscription containing all provisioning and telecom configuration';
COMMENT ON COLUMN tenant_apps.subscription_id IS 'Reference to subscription in product-service';
COMMENT ON COLUMN tenant_apps.deployment_model IS 'SHARED = multi-tenant infra, DEDICATED = isolated namespace';
COMMENT ON COLUMN tenant_apps.sip_endpoint_username IS 'Kamailio subscriber for inbound DID registration';
COMMENT ON COLUMN tenant_apps.tenant_trunk_username IS 'Customer SIP trunk credentials to connect to platform';
COMMENT ON COLUMN tenant_apps.platform_trunk_provider IS 'Upstream trunk provider (EPSILON, DIDWW, etc)';
COMMENT ON COLUMN tenant_apps.cc_builder_config IS 'Generated FreePBX/FreeSWITCH dialplan configuration';
COMMENT ON COLUMN tenant_apps.kamailio_config IS 'Generated Kamailio configuration snippet';
COMMENT ON COLUMN tenant_apps.app_panels IS 'JSON array of UI apps with URLs and Keycloak clients';

-- Trigger for updated_at
CREATE OR REPLACE FUNCTION update_tenant_apps_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_tenant_apps_updated_at ON tenant_apps;
CREATE TRIGGER trigger_tenant_apps_updated_at
    BEFORE UPDATE ON tenant_apps
    FOR EACH ROW
    EXECUTE FUNCTION update_tenant_apps_updated_at();


-- ============================================================
-- V14__enhance_tenant_apps.sql
-- Add all missing columns to tenant_apps for complete telecom config
-- ============================================================

-- ==================== ADDITIONAL ENTITLEMENTS ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS max_supervisors INT;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS max_ring_groups INT;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS max_extensions INT;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS included_minutes_inbound INT;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS included_minutes_outbound INT;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS rate_per_minute_inbound DECIMAL(10,4);
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS rate_per_minute_outbound DECIMAL(10,4);

-- ==================== AI FEATURES (expanded) ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS ai_voice_morph_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS ai_agent_assist_enabled BOOLEAN DEFAULT false;

-- ==================== CALL FEATURES (expanded) ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS listen_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS blind_transfer_enabled BOOLEAN DEFAULT true;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS attended_transfer_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS warm_transfer_enabled BOOLEAN DEFAULT false;

-- ==================== RECORDING (expanded) ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS screen_recording_enabled BOOLEAN DEFAULT false;

-- ==================== IVR FEATURES ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS basic_ivr_enabled BOOLEAN DEFAULT true;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS conversational_ivr_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS ivr_multi_language_enabled BOOLEAN DEFAULT false;

-- ==================== DIALER FEATURES ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS progressive_dialer_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS predictive_dialer_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS preview_dialer_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS amd_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS dnc_management_enabled BOOLEAN DEFAULT false;

-- ==================== VOICEMAIL ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS voicemail_enabled BOOLEAN DEFAULT true;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS voicemail_transcription_enabled BOOLEAN DEFAULT false;

-- ==================== INTEGRATIONS ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS crm_integration_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS screen_pop_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS api_access_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS webhook_enabled BOOLEAN DEFAULT false;

-- ==================== REPORTING ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS basic_reporting_enabled BOOLEAN DEFAULT true;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS advanced_reporting_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS custom_reports_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS wallboard_enabled BOOLEAN DEFAULT false;

-- ==================== DEPLOYMENT ====================
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS dedicated_infrastructure BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS custom_domain_enabled BOOLEAN DEFAULT false;
ALTER TABLE tenant_apps ADD COLUMN IF NOT EXISTS sla_tier VARCHAR(20) DEFAULT 'STANDARD';

-- ==================== INDEXES ====================
CREATE INDEX IF NOT EXISTS idx_tenant_app_plan ON tenant_apps(plan_code);
CREATE INDEX IF NOT EXISTS idx_tenant_app_tier ON tenant_apps(plan_tier);