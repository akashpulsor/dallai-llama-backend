-- V10__agents_keycloak_user_id.sql
-- Add keycloak_user_id column to agents table for /me endpoint JWT resolution.
-- Also add indexes and unique constraints.

ALTER TABLE agents ADD COLUMN keycloak_user_id VARCHAR(100);

-- Keycloak user ID lookup (for GET /agents/me)
CREATE INDEX idx_agents_keycloak_user_id ON agents (keycloak_user_id)
    WHERE keycloak_user_id IS NOT NULL;

CREATE INDEX idx_agents_tenant_keycloak ON agents (tenant_id, keycloak_user_id)
    WHERE keycloak_user_id IS NOT NULL;

-- Extension must be unique within a tenant (two agents can't share 1001 in same tenant)
CREATE UNIQUE INDEX idx_agents_tenant_extension ON agents (tenant_id, extension)
    WHERE extension IS NOT NULL AND is_active = true;

-- Username must be unique within a tenant (SIP subscriber uniqueness)
CREATE UNIQUE INDEX idx_agents_tenant_username ON agents (tenant_id, username)
    WHERE is_active = true;