-- V12__subscriber_namespace.sql
-- Add namespace column so FreeSWITCH directory can set correct user_context.
-- user_context = "tenant_{namespace}" must match dialplan context in tenant_dialplan table.
-- Without this, directory returned tenant_{uuid} but dialplan is stored as tenant_{slug}.

ALTER TABLE subscriber ADD COLUMN namespace VARCHAR(64);

-- Backfill from domain: "tenant-acme.dalaillama.in" → "acme"
-- Handles the common pattern where domain starts with "tenant-"
UPDATE subscriber
SET namespace = REPLACE(SPLIT_PART(domain, '.', 1), 'tenant-', '')
WHERE namespace IS NULL AND domain LIKE 'tenant-%';

-- For any remaining rows where domain doesn't follow tenant- pattern, use tenant_id
UPDATE subscriber
SET namespace = tenant_id::text
WHERE namespace IS NULL;

-- Index for potential lookups
CREATE INDEX idx_subscriber_namespace ON subscriber(namespace);

ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS answered_at TIMESTAMP;
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS hangup_cause VARCHAR(50);

ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS crm_id VARCHAR(100);
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS crm_provider VARCHAR(30);
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'CSV';