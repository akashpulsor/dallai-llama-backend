-- V15__campaign_contacts_qualify_columns.sql
-- Adds columns for lead qualification, CRM linking, and contact source tracking.

ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS answered_at      TIMESTAMP;
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS hangup_cause     VARCHAR(50);
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS source           VARCHAR(20) DEFAULT 'CSV';
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS crm_id           VARCHAR(100);
ALTER TABLE campaign_contacts ADD COLUMN IF NOT EXISTS crm_provider     VARCHAR(30);

-- Index for filtering by status (qualified leads view)
CREATE INDEX IF NOT EXISTS idx_contacts_status_tenant
    ON campaign_contacts(tenant_id, status)
    WHERE status IN ('QUALIFIED', 'NOT_QUALIFIED', 'COMPLETED');
