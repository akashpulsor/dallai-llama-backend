-- ============================================================================
-- V4__create_tenant_apps.sql
-- Tenant apps table - stores provisioned apps for each tenant
-- ============================================================================

CREATE TABLE IF NOT EXISTS tenant_apps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Tenant reference
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- App identification
    app_type VARCHAR(50) NOT NULL,
    subdomain VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,

    -- Keycloak integration
    keycloak_client_id VARCHAR(100) NOT NULL,

    -- Kubernetes deployment
    frontend_service VARCHAR(100) NOT NULL,
    frontend_image VARCHAR(200) NOT NULL,
    frontend_port INTEGER NOT NULL DEFAULT 80,

    -- Access control
    required_roles VARCHAR(200),

    -- Display
    description VARCHAR(500),
    icon VARCHAR(50),
    display_order INTEGER NOT NULL DEFAULT 0,

    -- Status
    enabled BOOLEAN NOT NULL DEFAULT true,
    deployment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    deployed_at TIMESTAMP WITH TIME ZONE,

    -- Audit
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    -- Constraints
    CONSTRAINT uk_tenant_app_type UNIQUE (tenant_id, app_type),
    CONSTRAINT uk_tenant_subdomain UNIQUE (tenant_id, subdomain),
    CONSTRAINT chk_deployment_status CHECK (deployment_status IN ('PENDING', 'DEPLOYING', 'DEPLOYED', 'FAILED'))
);

-- Indexes
CREATE INDEX idx_tenant_apps_tenant_id ON tenant_apps(tenant_id);
CREATE INDEX idx_tenant_apps_enabled ON tenant_apps(tenant_id, enabled);
CREATE INDEX idx_tenant_apps_deployment_status ON tenant_apps(deployment_status);

-- Comments
COMMENT ON TABLE tenant_apps IS 'Provisioned apps for each tenant, created from product_apps during provisioning';
COMMENT ON COLUMN tenant_apps.app_type IS 'Type of app: CONTACT_CENTER, IVR_BUILDER, ADMIN_PANEL, etc.';
COMMENT ON COLUMN tenant_apps.subdomain IS 'Subdomain for the app, e.g., "ivr" for ivr.tenant.dalaillama.in';
COMMENT ON COLUMN tenant_apps.keycloak_client_id IS 'Tenant-specific Keycloak client ID, e.g., dalaillama-acme-ivr';
COMMENT ON COLUMN tenant_apps.frontend_service IS 'Kubernetes service name for the frontend deployment';
COMMENT ON COLUMN tenant_apps.deployment_status IS 'PENDING, DEPLOYING, DEPLOYED, FAILED';

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

