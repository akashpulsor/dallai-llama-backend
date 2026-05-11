-- V17__add_keycloak_endpoints_to_tenants.sql
-- Adds keycloak_url and keycloak_issuer to tenants table.
-- These are stamped during provisioning so the public tenant-config endpoint
-- can serve them to the frontend without recomputing from app properties.

ALTER TABLE tenants
    ADD COLUMN keycloak_url    VARCHAR(255),
    ADD COLUMN keycloak_issuer VARCHAR(500);

COMMENT ON COLUMN tenants.keycloak_url IS
    'Public Keycloak base URL the browser uses for OIDC login, e.g. https://auth.dalaillama.in';
COMMENT ON COLUMN tenants.keycloak_issuer IS
    'Full OIDC issuer URL for this tenant realm, e.g. https://auth.dalaillama.in/realms/tenant-{uuid}';

-- Optional backfill for existing tenants (uncomment + adjust if needed)
-- UPDATE tenants
-- SET keycloak_url = 'https://auth.dalaillama.in',
--     keycloak_issuer = 'https://auth.dalaillama.in/realms/' || keycloak_realm_name
-- WHERE keycloak_realm_name IS NOT NULL AND keycloak_url IS NULL;

-- V18__create_tenant_app_panels.sql
-- AppPanel as a child entity of Tenant. Accessed only through the parent —
-- no standalone repository, no direct service access.

CREATE TABLE tenant_app_panels (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    tenant_app_id        UUID REFERENCES tenant_apps(id) ON DELETE SET NULL,
    app_type             VARCHAR(50)  NOT NULL,
    display_name         VARCHAR(100) NOT NULL,
    subdomain            VARCHAR(50)  NOT NULL,
    url                  VARCHAR(500) NOT NULL,
    icon                 VARCHAR(20),
    display_order        INT NOT NULL DEFAULT 0,
    keycloak_client_id   VARCHAR(100) NOT NULL,
    required_roles       VARCHAR(200) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_client_id UNIQUE (tenant_id, keycloak_client_id)
);

CREATE INDEX idx_tenant_app_panels_tenant ON tenant_app_panels(tenant_id);
CREATE INDEX idx_tenant_app_panels_tenant_app ON tenant_app_panels(tenant_app_id);

COMMENT ON TABLE tenant_app_panels IS
    'App panels (admin/agent/supervisor UIs) owned by a tenant. One row per Keycloak client.';

-- Drop the obsolete JSON column from tenants (added in V16)
ALTER TABLE tenants DROP COLUMN IF EXISTS master_app_panel_json;

-- Drop the obsolete JSON column from tenant_apps (replaced by foreign key relation)
ALTER TABLE tenant_apps DROP COLUMN IF EXISTS app_panels;