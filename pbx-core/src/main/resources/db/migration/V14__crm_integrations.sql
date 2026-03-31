-- V10__crm_integrations.sql

CREATE TABLE crm_integrations (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    subscription_id UUID NOT NULL,
    provider        VARCHAR(30) NOT NULL,
    name            VARCHAR(100),
    api_url         VARCHAR(500),
    api_key         VARCHAR(500),
    client_id       VARCHAR(500),
    client_secret   VARCHAR(500),
    refresh_token   TEXT,
    access_token    TEXT,
    token_expires_at TIMESTAMP,
    field_mapping   JSONB DEFAULT '{}',
    sync_contacts   BOOLEAN DEFAULT true,
    sync_calls      BOOLEAN DEFAULT true,
    sync_notes      BOOLEAN DEFAULT true,
    is_active       BOOLEAN DEFAULT false,
    last_sync_at    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT now(),
    updated_at      TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_crm_integrations_tenant ON crm_integrations(tenant_id);

-- Add CRM fields to campaign_contacts
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS crm_id VARCHAR(100);
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS crm_provider VARCHAR(30);
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'CSV';
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS answered_at TIMESTAMP;
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS hangup_cause VARCHAR(50);

CREATE INDEX idx_campaign_contacts_crm ON campaign_contacts(crm_id) WHERE crm_id IS NOT NULL;