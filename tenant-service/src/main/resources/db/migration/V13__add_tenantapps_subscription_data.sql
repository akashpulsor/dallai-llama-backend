-- Migration: Add subscription data fields to tenant_apps

-- Subscription reference
ALTER TABLE tenant_apps ADD COLUMN subscription_id UUID UNIQUE;

-- Plan tier
ALTER TABLE tenant_apps ADD COLUMN plan_tier VARCHAR(30);

-- Entitlements
ALTER TABLE tenant_apps ADD COLUMN agent_seats INT;
ALTER TABLE tenant_apps ADD COLUMN max_agents INT;
ALTER TABLE tenant_apps ADD COLUMN max_dids INT;
ALTER TABLE tenant_apps ADD COLUMN max_channels INT;
ALTER TABLE tenant_apps ADD COLUMN included_minutes INT;
ALTER TABLE tenant_apps ADD COLUMN ai_rate_per_min DECIMAL(10,4);

-- DID
ALTER TABLE tenant_apps ADD COLUMN did_id UUID;
ALTER TABLE tenant_apps ADD COLUMN did_number VARCHAR(20);
ALTER TABLE tenant_apps ADD COLUMN did_display_number VARCHAR(30);
ALTER TABLE tenant_apps ADD COLUMN did_country VARCHAR(5);
ALTER TABLE tenant_apps ADD COLUMN did_region VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN did_city VARCHAR(100);

-- SIP Endpoint
ALTER TABLE tenant_apps ADD COLUMN sip_endpoint_id UUID;
ALTER TABLE tenant_apps ADD COLUMN sip_endpoint_username VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN sip_endpoint_password_hash VARCHAR(255);
ALTER TABLE tenant_apps ADD COLUMN sip_endpoint_domain VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN sip_endpoint_realm VARCHAR(100);

-- Channels
ALTER TABLE tenant_apps ADD COLUMN channel_bundle_id UUID;
ALTER TABLE tenant_apps ADD COLUMN channel_direction VARCHAR(20);
ALTER TABLE tenant_apps ADD COLUMN channel_total INT;
ALTER TABLE tenant_apps ADD COLUMN channel_inbound INT;
ALTER TABLE tenant_apps ADD COLUMN channel_outbound INT;

-- Tenant SIP Trunk
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_id UUID;
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_username VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_password_hash VARCHAR(255);
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_domain VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_port INT;
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_realm VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_transport VARCHAR(10);
ALTER TABLE tenant_apps ADD COLUMN tenant_trunk_max_calls INT;

-- Platform Trunk
ALTER TABLE tenant_apps ADD COLUMN platform_trunk_id UUID;
ALTER TABLE tenant_apps ADD COLUMN platform_trunk_provider VARCHAR(50);
ALTER TABLE tenant_apps ADD COLUMN platform_trunk_server VARCHAR(100);
ALTER TABLE tenant_apps ADD COLUMN platform_trunk_port INT;
ALTER TABLE tenant_apps ADD COLUMN platform_trunk_transport VARCHAR(10);
ALTER TABLE tenant_apps ADD COLUMN platform_trunk_codecs JSONB;

-- App Panels (JSON)
ALTER TABLE tenant_apps ADD COLUMN app_panels JSONB;

-- Index
CREATE INDEX idx_tenant_app_subscription ON tenant_apps(subscription_id);
CREATE INDEX idx_tenant_app_did ON tenant_apps(did_number);