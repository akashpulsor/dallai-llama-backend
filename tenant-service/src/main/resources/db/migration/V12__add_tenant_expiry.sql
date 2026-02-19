-- V5__add_tenant_expiry.sql
-- Add expiry and activation tracking for tenants

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS activated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS keycloak_configured BOOLEAN DEFAULT FALSE;

-- Index for cleanup job
CREATE INDEX IF NOT EXISTS idx_tenant_expires
ON tenants(expires_at)
WHERE status = 'CREATED' AND expires_at IS NOT NULL;

-- Comments
COMMENT ON COLUMN tenants.expires_at IS 'When tenant expires if not subscribed. NULL = permanent';
COMMENT ON COLUMN tenants.activated_at IS 'When first subscription became active';
COMMENT ON COLUMN tenants.keycloak_configured IS 'Keycloak tenant_id attribute and roles configured';